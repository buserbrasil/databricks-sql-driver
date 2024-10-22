(ns metabase.driver.databricks-sql.connection
  (:require [clojure.reflect]
            [clojure.set])
  (:import [java.sql ResultSet SQLException]))

(defmacro decorator
  [clazz proto & fs]
  (let [proto-name (gensym "proto")
        methods (->> (clojure.reflect/reflect (resolve clazz))
                     :members
                     (filter #(instance? clojure.reflect.Method %))
                     (map (fn [{:keys [name parameter-types]}]
                            [name (count parameter-types)]))
                     set)
        to-delegate (clojure.set/difference
                     methods
                     (->> fs
                          (map (fn [[name params]]
                                 [name (count params)]))
                          set))
        method-bodies
        (concat
         fs ;; these are our own definitions
         (for [[name n-params] to-delegate]
           (let [params (->> (range n-params)
                             (map #(gensym (str "x" %))))]
             `(~name [~@params]
                     (. ~proto-name (~name ~@params))) ;; this is where we delegate to the prototype
             )))]
    `(let [~proto-name ~proto]
       (proxy
        [~clazz] []
         ~@(->> method-bodies (group-by first) (sort-by first)
                (map (fn [[name bodies]]
                       `(~name ~@(for [[name & rest] bodies]
                                   rest)))))))))

(def get-holdability-fn
  ResultSet/CLOSE_CURSORS_AT_COMMIT)

(defn- set-read-only-fn
  [conn read-only?]
  (when (.isClosed conn)
    (throw (SQLException. "Connection is closed")))
  (when read-only?
    (throw (SQLException. "Enabling read-only mode is not supported"))))

(defn decorate-and-fix
  [impl]
  (when impl
    (decorator
     java.sql.Connection
     impl
     (getHoldability
      []
      get-holdability-fn)
     (setReadOnly
      [read-only?]
      (set-read-only-fn this read-only?)))))
