(ns metabase.driver.databricks-sql
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [medley.core :as m]
            [metabase.driver :as driver]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
            [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.driver.sql.util :as sql.u]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase.mbql.util :as mbql.u]
            [clojure.string :as str]
            [metabase.query-processor.util :as qp.util])
  (:import [java.sql Connection ResultSet]))

(driver/register! :databricks-sql, :parent :sql-jdbc)

(defmethod sql-jdbc.conn/connection-details->spec :databricks-sql
  [_ {:keys [host http-path token db]}]
  {:classname        "com.databricks.client.jdbc.Driver"
   :subprotocol      "databricks"
   :subname          (str "//" host ":443/" db)
   :transportMode    "http"
   :ssl              1
   :AuthMech         3
   :httpPath         http-path
   :uid              "token"
   :pwd              token})

(defmethod sql-jdbc.conn/data-warehouse-connection-pool-properties :databricks-sql
  "The Hive JDBC driver doesn't support `Connection.isValid()`,
  so we need to supply a test query for c3p0 to use to validate
  connections upon checkout."
  [driver database]
  (merge
   ((get-method sql-jdbc.conn/data-warehouse-connection-pool-properties :sql-jdbc) driver database)
   {"preferredTestQuery" "SELECT 1"}))

(defmethod sql-jdbc.sync/database-type->base-type :databricks-sql
  [_ database-type]
  (condp re-matches (str/lower-case (name database-type))
    #"boolean"          :type/Boolean
    #"tinyint"          :type/Integer
    #"smallint"         :type/Integer
    #"int"              :type/Integer
    #"bigint"           :type/BigInteger
    #"float"            :type/Float
    #"double"           :type/Float
    #"double precision" :type/Double
    #"decimal.*"        :type/Decimal
    #"char.*"           :type/Text
    #"varchar.*"        :type/Text
    #"string.*"         :type/Text
    #"binary*"          :type/*
    #"date"             :type/Date
    #"time"             :type/Time
    #"timestamp"        :type/DateTime
    #"interval"         :type/*
    #"array.*"          :type/Array
    #"map"              :type/Dictionary
    #".*"               :type/*))

(defmethod driver/describe-database :databricks-sql
  "workaround for SPARK-9686 Spark Thrift server doesn't return correct JDBC metadata"
  [_ database]
  {:tables
   (with-open [conn (jdbc/get-connection (sql-jdbc.conn/db->pooled-connection-spec database))]
     (set
      (for [{:keys [database tablename], table-namespace :namespace} (jdbc/query {:connection conn} ["show tables"])]
        {:name   tablename
         :schema (or (not-empty database)
                     (not-empty table-namespace))})))})

(defn- valid-describe-table-row?
  "Hive describe table result has commented rows to distinguish partitions"
  [{:keys [col_name data_type]}]
  (every? (every-pred (complement str/blank?)
                      (complement #(str/starts-with? % "#")))
          [col_name data_type]))

(defn- dash-to-underscore [s]
  (when s
    (str/replace s #"-" "_")))

(defmethod driver/describe-table :databricks-sql
  "workaround for SPARK-9686 Spark Thrift server doesn't return correct JDBC metadata"
  [driver database {table-name :name, schema :schema}]
  {:name   table-name
   :schema schema
   :fields
   (with-open [conn (jdbc/get-connection (sql-jdbc.conn/db->pooled-connection-spec database))]
     (let [results (jdbc/query {:connection conn} [(format
                                                    "describe %s"
                                                    (sql.u/quote-name driver :table
                                                                      (dash-to-underscore schema)
                                                                      (dash-to-underscore table-name)))])]
       (set
        (for [[idx {col-name :col_name, data-type :data_type, :as result}] (m/indexed results)
              :while (valid-describe-table-row? result)]
          {:name              col-name
           :database-type     data-type
           :base-type         (sql-jdbc.sync/database-type->base-type :databricks-sql (keyword data-type))
           :database-position idx}))))})

(def ^:dynamic *param-splice-style*
  "How we should splice params into SQL (i.e. 'unprepare' the SQL). Either `:friendly` (the default) or `:paranoid`.
  `:friendly` makes a best-effort attempt to escape strings and generate SQL that is nice to look at, but should not
  be considered safe against all SQL injection -- use this for 'convert to SQL' functionality. `:paranoid` hex-encodes
  strings so SQL injection is impossible; this isn't nice to look at, so use this for actually running a query."
  :friendly)

(defmethod driver/execute-reducible-query :databricks-sql
  "bound variables are not supported in Spark SQL (maybe not Hive either, haven't checked)"
  [driver {{sql :query, :keys [params], :as inner-query} :native, :as outer-query} context respond]
  (let [inner-query (-> (assoc inner-query
                               :remark (qp.util/query->remark :databricks-sql outer-query)
                               :query  (if (seq params)
                                         (binding [*param-splice-style* :paranoid]
                                           (unprepare/unprepare driver (cons sql params)))
                                         sql)
                               :max-rows (mbql.u/query->max-rows-limit outer-query))
                        (dissoc :params))
        query       (assoc outer-query :native inner-query)]
    ((get-method driver/execute-reducible-query :sql-jdbc) driver query context respond)))

(defmethod sql-jdbc.execute/connection-with-timezone :databricks-sql
  "1. SparkSQL doesn't support `.supportsTransactionIsolationLevel`
   2. SparkSQL doesn't support session timezones (at least our driver doesn't support it)
   3. SparkSQL doesn't support making connections read-only
   4. SparkSQL doesn't support setting the default result set holdability"
  [driver database _timezone-id]
  (let [conn (.getConnection (sql-jdbc.execute/datasource-with-diagnostic-info! driver database))]
    (try
      (.setTransactionIsolation conn Connection/TRANSACTION_READ_UNCOMMITTED)
      conn
      (catch Throwable e
        (.close conn)
        (throw e)))))

(defmethod sql-jdbc.execute/prepared-statement :databricks-sql
  "1. SparkSQL doesn't support setting holdability type to `CLOSE_CURSORS_AT_COMMIT`"
  [driver ^Connection conn ^String sql params]
  (let [stmt (.prepareStatement conn sql
                                ResultSet/TYPE_FORWARD_ONLY
                                ResultSet/CONCUR_READ_ONLY)]
    (try
      (.setFetchDirection stmt ResultSet/FETCH_FORWARD)
      (sql-jdbc.execute/set-parameters! driver stmt params)
      stmt
      (catch Throwable e
        (.close stmt)
        (throw e)))))

(defmethod sql-jdbc.execute/statement-supported? :databricks-sql
  "the current HiveConnection doesn't support .createStatement"
  [_]
  false)

(doseq [feature [:basic-aggregations
                 :binning
                 :expression-aggregations
                 :expressions
                 :native-parameters
                 :nested-queries
                 :standard-deviation-aggregations]]
  (defmethod driver/supports? [:databricks-sql feature] [_ _] true))

;; only define an implementation for `:foreign-keys` if none exists already. In test extensions we define an alternate
;; implementation, and we don't want to stomp over that if it was loaded already
(when-not (get (methods driver/supports?) [:databricks-sql :foreign-keys])
  (defmethod driver/supports? [:databricks-sql :foreign-keys] [_ _] true))

(defmethod sql.qp/quote-style :databricks-sql [_] :mysql)
