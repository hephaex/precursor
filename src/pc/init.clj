(ns pc.init
  (:require pc.datomic
            pc.less
            pc.logging
            pc.nrepl
            pc.server))

(def init-fns [#'pc.logging/init
               #'pc.nrepl/init
               #'pc.less/init
               #'pc.datomic/init
               #'pc.server/init])

(defn pretty-now []
  (.toLocaleString (java.util.Date.)))

(defn init []
  (doseq [f init-fns]
    (println (pretty-now) f)
    (f)))

(defn -main []
  (init)
  (println (pretty-now) "done"))
