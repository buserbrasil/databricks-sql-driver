(ns metabase.driver.databricks-sql
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [medley.core :as m]
            [metabase.driver :as driver]
            [metabase.driver.sql-jdbc.common :as sql-jdbc.common]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
            [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.driver.sql.util :as sql.u]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase.util.log :as log]
            [metabase.legacy-mbql.util :as mbql.u]
            [metabase.query-processor.util :as qp.util]
            [clj-http.client :as http]
            [cheshire.core :as json])
  (:import [java.sql Connection ResultSet SQLException]))

(set! *warn-on-reflection* true)

(driver/register! :databricks-sql, :parent :sql-jdbc)

(defn- sparksql-databricks
  "Create a database specification for a Spark SQL database."
  [{:keys [host db jdbc-flags] :as opts}]
  (merge
   {:classname   "metabase.driver.databricks-sql.FixedDatabricksDriver"
    :subprotocol "databricks"
    :subname     (str "//" host ":443/" db jdbc-flags)}
   (dissoc opts :host :db :jdbc-flags)))

(defn- build-jdbc-flags
  [details]
  (str ";transportMode=http"
       ";ssl=1"
       ";AuthMech=3"
       ";LogLevel=0"
       ";UID=token"
       ";PWD=" (:token details)
       ";httpPath=" (:http-path details)))

(defmethod sql-jdbc.conn/connection-details->spec :databricks-sql
  [_ details]
  (-> details
      (assoc :jdbc-flags (build-jdbc-flags details))
      (select-keys [:host :db :jdbc-flags :dbname])
      sparksql-databricks
      (sql-jdbc.common/handle-additional-options details)))

(defmethod sql-jdbc.conn/data-warehouse-connection-pool-properties :databricks-sql
  [driver database]
  (merge
   ((get-method sql-jdbc.conn/data-warehouse-connection-pool-properties :sql-jdbc) driver database)
   {"preferredTestQuery" "SELECT 1"}))

(defn- map-database-type
  [database-type]
  (let [db-type (string/lower-case (name database-type))]
    (cond
      (re-matches #"boolean" db-type)          :type/Boolean
      (re-matches #"tinyint" db-type)          :type/Integer
      (re-matches #"smallint" db-type)         :type/Integer
      (re-matches #"int" db-type)              :type/Integer
      (re-matches #"bigint" db-type)           :type/BigInteger
      (re-matches #"float" db-type)            :type/Float
      (re-matches #"double" db-type)           :type/Float
      (re-matches #"double precision" db-type) :type/Double
      (re-matches #"decimal.*" db-type)        :type/Decimal
      (re-matches #"char.*" db-type)           :type/Text
      (re-matches #"varchar.*" db-type)        :type/Text
      (re-matches #"string.*" db-type)         :type/Text
      (re-matches #"binary*" db-type)          :type/*
      (re-matches #"date" db-type)             :type/Date
      (re-matches #"time" db-type)             :type/Time
      (re-matches #"timestamp" db-type)        :type/DateTime
      (re-matches #"interval" db-type)         :type/*
      (re-matches #"array.*" db-type)          :type/Array
      (re-matches #"map" db-type)              :type/Dictionary
      :else                                    :type/*)))

(defmethod sql-jdbc.sync/database-type->base-type :databricks-sql
  [_ database-type]
  (map-database-type database-type))

(defmethod sql.qp/honey-sql-version :databricks-sql
  [_driver]
  2)

(defn- dash-to-underscore
  [s]
  (some-> s
          (string/replace #"-" "_")))

(defn- fetch-tables
  [conn]
  (->> (jdbc/query {:connection conn} ["show tables"])
       (map (fn [{:keys [database tablename tab_name], table-namespace :namespace}]
              {:name   (or tablename tab_name) ; column name differs dependendo do servidor (SparkSQL, hive, Impala)
               :schema (or (not-empty database)
                           (not-empty table-namespace))}))
       (into #{})))

(defmethod driver/describe-database :databricks-sql
  [driver database]
  {:tables
   (sql-jdbc.execute/do-with-connection-with-options
    driver
    database
    nil
    fetch-tables)})

(defn- valid-describe-table-row?
  [{:keys [col_name data_type]}]
  (every? (every-pred (complement string/blank?)
                      (complement #(string/starts-with? % "#")))
          [col_name data_type]))

(defn- fetch-table-fields
  [driver conn schema table-name]
  (let [results (jdbc/query {:connection conn} [(format
                                                 "describe %s"
                                                 (sql.u/quote-name driver :table
                                                                   (dash-to-underscore schema)
                                                                   (dash-to-underscore table-name)))])]
    (set
     (for [[idx {col-name :col_name, data-type :data_type, :as result}] (m/indexed results)
           :when (valid-describe-table-row? result)]
       {:name              col-name
        :database-type     data-type
        :base-type         (sql-jdbc.sync/database-type->base-type :hive-like (keyword data-type))
        :database-position idx}))))

(defmethod driver/describe-table :databricks-sql
  [driver database {table-name :name, schema :schema}]
  {:name   table-name
   :schema schema
   :fields
   (sql-jdbc.execute/do-with-connection-with-options
    driver
    database
    nil
    (fn [^Connection conn]
      (fetch-table-fields driver conn schema table-name)))})

(def ^:dynamic *param-splice-style*
  "How we should splice params into SQL (i.e. 'unprepare' the SQL). Either `:friendly` (the default) or `:paranoid`.
  `:friendly` makes a best-effort attempt to escape strings and generate SQL that is nice to look at, but should not
  be considered safe against all SQL injection -- use this for 'convert to SQL' functionality. `:paranoid` hex-encodes
  strings so SQL injection is impossible; this isn't nice to look at, so use this for actually running a query."
  :friendly)

(defn- kill-databricks-query
  [execution-id token]
  (http/post (str "https://<databricks-instance>/api/2.0/sql/statements/cancel")
             {:headers {"Authorization" (str "Bearer " token)}
              :body    (json/write-str {:statement_id execution-id})}))

(defmethod driver/execute-reducible-query :databricks-sql
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
    (try
      ((get-method driver/execute-reducible-query :sql-jdbc) driver query context respond)
      (catch SQLException e
        (let [execution-id (.getExecutionId e)
              token        (:token (sql-jdbc.conn/connection-details->spec :databricks-sql query))]
          (kill-databricks-query execution-id token)
          (throw e))))))

(defmethod sql-jdbc.execute/do-with-connection-with-options :databricks-sql
  [driver db-or-id-or-spec options f]
  (sql-jdbc.execute/do-with-resolved-connection
   driver
   db-or-id-or-spec
   options
   (fn [^Connection conn]
     (when-not (sql-jdbc.execute/recursive-connection?)
       (.setTransactionIsolation conn Connection/TRANSACTION_READ_UNCOMMITTED))
     (log/trace (pr-str '(.setHoldability conn ResultSet/CLOSE_CURSORS_AT_COMMIT)))
     (.setHoldability conn ResultSet/CLOSE_CURSORS_AT_COMMIT)
     (f conn))))

(defmethod sql-jdbc.execute/prepared-statement :databricks-sql
  [driver ^Connection conn ^String sql params]
  (let [stmt (.prepareStatement conn sql
                                ResultSet/TYPE_FORWARD_ONLY
                                ResultSet/CONCUR_READ_ONLY
                                ResultSet/CLOSE_CURSORS_AT_COMMIT)]
    (try
      (.setFetchDirection stmt ResultSet/FETCH_FORWARD)
      (sql-jdbc.execute/set-parameters! driver stmt params)
      stmt
      (catch Throwable e
        (.close stmt)
        (throw e)))))

(defmethod sql-jdbc.execute/statement-supported? :databricks-sql [_] false)

(doseq [[feature supported?] {:basic-aggregations              true
                              :binning                         true
                              :expression-aggregations         true
                              :expressions                     true
                              :native-parameters               true
                              :nested-queries                  true
                              :standard-deviation-aggregations true
                              :metadata/key-constraints        false
                              :test/jvm-timezone-setting       false}]
  (defmethod driver/database-supports? [:databricks-sql feature] [_driver _feature _db] supported?))

(when-not (get (methods driver/database-supports?) [:databricks-sql :foreign-keys])
  (defmethod driver/database-supports? [:databricks-sql :foreign-keys] [_driver _feature _db] true))

(defmethod sql.qp/quote-style :databricks-sql
  [_driver]
  :mysql)
