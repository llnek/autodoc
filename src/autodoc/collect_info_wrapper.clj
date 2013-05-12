(ns autodoc.collect-info-wrapper
  (:require
   [clojure.string :as str])

  (:use
   [clojure.java.io :only [file reader]]
   [clojure.java.shell :only [sh]]
   [clojure.pprint :only [cl-format pprint]]
   [autodoc.deps :only [find-jars]]
   [autodoc.params :only (params expand-classpath)]
   [autodoc.pom-tools :only (get-dependencies)])

  (:import [java.io File]))

;;; The code to execute collect-info in a separate process

(defn- build-sh-args [args]
  (concat (str/split (first args) #"\s+") (rest args)))

(defn system [& args]
  (pprint args)
  (println (:out (apply sh (build-sh-args args)))))

(defn path-str [path-seq] 
  (apply str (interpose (System/getProperty "path.separator")
                        (map #(.getAbsolutePath (file %)) path-seq))))

(defn autodoc-jar
  "Sort through the classpath and see if the autodoc jar is in there. This is an indication
that autodoc was invoked from a jar rather than out of its source directory."
  []
  (seq (filter #(re-matches #".*/autodoc-[^/]+\.jar$" %)
               (str/split (get (System/getProperties) "java.class.path")
                          (re-pattern (System/getProperty "path.separator"))))))

(defn exec-clojure [class-path & args]
  (println "@exec-clojure: classpath = " class-path)
  (apply system (concat [ "java" "-cp"] 
                        [(path-str class-path)]
                        ["clojure.main" "-e"]
                        args)))

(defn expand-jar-path [jar-dirs]
  (apply concat 
         (for [jar-dir jar-dirs]
           (filter #(.endsWith (.getName %) ".jar")
                   (file-seq (java.io.File. jar-dir))))))

(defn do-collect 
  "Collect the namespace and var info for the checked out branch by spawning a separate process.
This means that we can keep versions and dependencies unentangled "
  [branch-name]
  (let [src-path (map #(.getPath (File. (params :root) %)) (params :source-path))
        target-path (.getPath (File. (params :root) "target/classes"))
        class-path (concat 
                    (filter 
                     identity
                     (concat
                      [(params :built-clojure-jar)]
                      (autodoc-jar)
                      ["src"]
                      src-path
                      [target-path "."]))
                    (when-let [deps (get-dependencies (params :root) (params :dependencies))]
                      (find-jars {:local-repo-classpath true,
                                  :dependencies deps,
                                  :root (params :root)
                                  :name (str "Autodoc for " (params :name))}))
                    (expand-classpath branch-name (params :root) (params :load-classpath))
                    (expand-jar-path (params :load-jar-dirs)))
        tmp-file (File/createTempFile "collect-" ".clj")]
    (exec-clojure class-path 
                  (cl-format 
                   nil 
                   "(use 'autodoc.collect-info) (collect-info-to-file \"~a\" \"~a\" \"~a\" \"~a\" \"~a\")"
                   (params :param-file)
                   (params :param-key)
                   (params :param-dir)
                   (.getAbsolutePath tmp-file)
                   branch-name))
    (try 
      (with-open [f (java.io.PushbackReader. (reader tmp-file))] 
        (binding [*in* f] (read)))
      (finally 
       (.delete tmp-file)))))