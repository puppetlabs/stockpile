(ns stockpile-test
  (:require [stockpile :as stock]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer :all])
  (:import [org.apache.commons.lang3 RandomStringUtils]
           [java.io ByteArrayInputStream File]
           [java.nio.file Files NoSuchFileException]
           [java.nio.file.attribute FileAttribute]))

(defn- random-path-segment [n]
  (loop [s (RandomStringUtils/random n)]
    (if (and (= -1 (.indexOf s java.io.File/separator))
             (= -1 (.indexOf s (int \u0000))))
      s
      (recur (RandomStringUtils/random n)))))

(defn rm-r [pathstr]
  ;; Life's too short...
  (assert (zero? (:exit (shell/sh "rm" "-r" pathstr)))))

(defn call-with-temp-dir-path
  [f]
  (let [tempdir (Files/createTempDirectory (.toPath (File. "target"))
                                           "stockpile-test-"
                                           (into-array FileAttribute []))
        tempdirstr (str (.toAbsolutePath tempdir))
        result (try
                 (f (.toAbsolutePath tempdir))
                 (catch Exception ex
                   (binding [*out* *err*]
                     (println "Error: leaving temp dir" tempdirstr))
                   (throw ex)))]
    (rm-r tempdirstr)
    result))

(defn slurp-entry [q entry]
  (slurp (stock/stream q entry)))

(defn store-str
  ([q s] (store-str q s nil))
  ([q s metadata]
   (stock/store q (-> s (.getBytes "UTF-8") ByteArrayInputStream.) metadata)))

(deftest basics
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [q (stock/create (.toFile (.resolve tmpdir "queue")))]

       (store-str q "foo")

       (let [entry-1 (store-str q "foo")
             entry-2 (store-str q "bar" "*so* meta")
             id-1 (stock/entry-id entry-1)
             id-2 (stock/entry-id entry-2)
             meta-1 (stock/entry-meta entry-1)
             meta-2 (stock/entry-meta entry-2)]
         (is id-1)
         (is id-2)
         (is (< id-1 id-2))
         (is (> id-2 id-1))
         (is (not meta-1))
         (is (= "*so* meta" meta-2))
         (is (= "foo" (slurp-entry q entry-1)))
         (is (= "bar" (slurp-entry q entry-2)))

         (stock/discard q entry-1)
         (is (= "bar" (slurp-entry q entry-2)))
         ;; Implementation detail, but worth checking for current implementation
         (is (thrown? NoSuchFileException
                      (slurp-entry q entry-1))))))))

(deftest basic-persistence
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [qdir (.toFile (.resolve tmpdir "queue"))
           newq (stock/create qdir)]
       (let [entry-1 (store-str newq "foo" "meta foo")
             [q read-entries] (stock/open qdir conj ())]
         (is (= [entry-1] read-entries))
         (is (= "foo" (slurp-entry q entry-1)))
         (let [entry-2 (store-str q "bar" "meta bar")]
           (let [[q read-entries] (stock/open qdir conj #{})]
             (is (= #{entry-2 entry-1} read-entries))
             (is (= "foo" (slurp-entry q entry-1)))
             (is (= "bar" (slurp-entry q entry-2))))))))))

(deftest entry-manipulation
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [qdir (.toFile (.resolve tmpdir "queue"))
           q (stock/create qdir)
           inputs (for [i (range 10)] [(str i) (str "meta-" i)])
           entries (for [[data metadata] inputs]
                     (store-str q data metadata))]
       (doall
        (map (fn [input entry]
               (let [id (stock/entry-id entry)
                     metadata (stock/entry-meta entry)
                     reconstituted (stock/entry id metadata)]
                 (is (= entry reconstituted))
                 (is (= (first input)
                        (slurp-entry q reconstituted)))))
             inputs
             entries))))))

(deftest meta-encoding-round-trip
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [qdir (.toFile (.resolve tmpdir "queue"))
           q (stock/create qdir)
           batch-size 100]
       (dotimes [i batch-size]
         ;; We need to use a very short length here to avoid falling
         ;; afoul of path length limits since 8 random unicode
         ;; chars could expand to say 36 encoded bytes.
         (let [metadata (random-path-segment (rand-int 8))
               entry (store-str q metadata metadata)]
           (is (.startsWith metadata (stock/entry-meta entry)))))))))

(deftest existing-tmp-removal
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [qdir (.toFile (.resolve tmpdir "queue"))
           garbage (File. qdir "q/tmp-garbage")]
       (stock/create qdir)
       (io/copy "foo" (File. qdir "q/tmp-garbage"))
       (let [[q entries] (stock/open qdir conj ())]
         (is (= [] entries))
         (is (not (.exists garbage))))))))

(def billion 1000000000)

(defn enqueue-all [q items]
  (doall (for [item items]
           (apply store-str item))))

(deftest uncontended-performance
  ;; This also tests random metadata round trips
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [qdir (.toFile (.resolve tmpdir "queue"))]
       (doall
        (for [make-meta [nil #(random-path-segment 4)]
              batch-size [100 1000]
              i (range 3)]
          (do
            (let [q (stock/create qdir)
                  ;; Uncontended enqueue
                  start (System/nanoTime)
                  items (doall (for [i (range batch-size)]
                                 (let [m (and make-meta (make-meta))
                                       ent (store-str q (str i) m)]
                                   (when m
                                     (is (.startsWith m (stock/entry-meta ent))))
                                   [m ent])))
                  stop (System/nanoTime)
                  _ (binding [*out* *err*]
                      (printf "Enqueued %d tiny messages %s metadata at %.2f/s\n"
                              batch-size
                              (if make-meta "with" "without")
                              (double (/ batch-size (/ (- stop start) billion))))
                      (flush))

                  ;; Uncontended dequeue
                  start (System/nanoTime)
                  _ (is (= (set (map str (range batch-size)))
                           (set (for [[metadata entry] items]
                                  (slurp-entry q entry)))))
                  stop (System/nanoTime)
                  _ (binding [*out* *err*]
                      (printf "Dequeued %d tiny messages %s metadata at %.2f/s\n"
                              batch-size
                              (if make-meta "with" "without")
                              (double (/ batch-size (/ (- stop start) billion))))
                      (flush))]
              true)
            (rm-r (.getAbsolutePath qdir)))))))))

(deftest contending-enqueue-dequeue-performance
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [qdir (.toFile (.resolve tmpdir "queue"))
           q (stock/create qdir)
           batch-size 2000
           start (System/nanoTime)
           entries (seque (int (max 100 (/ batch-size 10)))
                          (for [i (range batch-size)]
                            (store-str q (str i))))]
       (doall
        (map (fn [i entry]
               (is (= (str i) (slurp-entry q entry)))
               (stock/discard q entry)
               ;; Implementation detail, but worth checking for
               ;; current implementation
               (is (thrown? NoSuchFileException
                            (slurp-entry q entry))))
             (range batch-size)
             entries))
       (binding [*out* *err*]
         (printf "Enqueued and dequeued %d tiny messages in parallel at %.2f/s\n"
                 batch-size
                 (double (/ batch-size
                            (/ (- (System/nanoTime) start)
                               billion))))
         (flush))))))

(deftest simple-race
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [batch-size 300
           qdir (.toFile (.resolve tmpdir "queue"))
           q (stock/create qdir)
           state (atom {:entries () :victim nil})
           finished? (atom false)
           writer (future
                    (dotimes [i batch-size]
                      (swap! state update :entries conj
                             [i (store-str q (str i) (str "meta-" i))])))
           reader (future
                    (while (not @finished?)
                      (let [{:keys [victim]} (swap! state
                                                    (fn [{[v & r] :entries}]
                                                      {:entries r
                                                       :victim v}))
                            [val entry] victim]
                        (when victim
                          (is (= (str val) (slurp-entry q entry)))
                          (swap! state update :entries conj victim)))))
           discarder (future
                       (loop [i 0]
                         (when (< i batch-size)
                           (let [{:keys [victim]} (swap! state
                                                         (fn [{[v & r] :entries}]
                                                           {:entries r
                                                            :victim v}))
                                 [val entry] victim]
                             (if entry
                               (do
                                 (stock/discard q entry)
                                 (recur (inc i)))
                               (recur i))))))]
       @writer @discarder
       (reset! finished? true)
       @reader))))
