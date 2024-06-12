(ns metabase.driver.FixedDatabricksDriver
  (:gen-class
   :extends com.databricks.client.jdbc.Driver
   :exposes-methods {connect superConnect}
   :init init
   :prefix "driver-"
   :constructors {[] []})
  (:require [metabase.driver.connection :as connection]))

(defn driver-init
  "Initializes the Spark driver"
  []
  [[] nil])

(defn driver-connect
  "Connects to a Spark database, fixing the connection to with Metabase"
  [^com.databricks.client.jdbc.Driver this, ^String url, ^java.util.Properties info]
  (connection/decorate-and-fix (.superConnect this url info)))