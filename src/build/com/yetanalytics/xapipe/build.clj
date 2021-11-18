(ns com.yetanalytics.xapipe.build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.yetanalytics/xapipe)

(def class-dir "target/classes")

(def basis
  (b/create-basis
   {:project "deps.edn"
    :aliases [:cli]}))

(def uber-file (format "target/bundle/%s.jar" (name lib)))

(defn uber [_]
  (b/copy-dir {:src-dirs ["src/lib" "src/cli" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src/lib" "src/cli"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'com.yetanalytics.xapipe.main}))
