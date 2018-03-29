(ns registration.core
  (:gen-class)
  (:require [dk.ative.docjure.spreadsheet :as ss]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [ring.middleware.params :refer :all]
            [ring.util.response :refer :all]
            [ring.middleware.multipart-params :refer :all]
            [ring.adapter.jetty :as jetty])
  (:import [org.apache.commons.io IOUtils]
           [java.time LocalDate]))

;; BEGIN READ VACATION SHEET

(defn process-cell [cell]
  (let [value (.getStringCellValue cell)]
    (cond
      (re-matches #".*(0\\.5|0,5).*" value) :half
      (re-matches #"(?i).*AL.*" value) :vacation
      (re-matches #"(?i).*SL.*" value) :sick
      (= "-" value) :holiday
      :else :full)))

(defn process-row [skip month-days row]
  (let [all (iterator-seq (.cellIterator row))
        [name-cell] all
        name (.getStringCellValue name-cell)
        skipped (drop skip all)
        cells (take month-days skipped)
        days (if-not (empty? name) (seq (map process-cell cells)) nil)]
    (if-not (nil? days)
      {:name name :days days}
      nil)))

(defn is-number [cell]
  (try
    (not (zero? (.getNumericCellValue cell)))
    (catch Exception e false)))

(defn not-number [cell]
  (not (is-number cell)))

(defn get-days [row]
  (let [all (reverse (iterator-seq (.cellIterator row)))
        remove-filler (drop-while not-number all)
        {days true empty false} (group-by is-number remove-filler)
        empty-count (count empty)
        days-count (count days)]
    {:empty empty-count :days days-count}))

(defn filter-by-name [name row]
  (let [cell (.getCell row 0)
        cellName (.getStringCellValue cell)]
    (= cellName name)))

(defn read-vacation [name month file]
  (with-open [is (io/input-stream file)
              workbook (ss/load-workbook is)]
    (let [sheet (ss/select-sheet month workbook)
          rows  (ss/row-seq sheet)
          [month-row & rows] (drop 4 rows)
          {empty :empty days :days} (get-days month-row)
          selected (filter (partial filter-by-name name) rows)]
      (remove nil? (seq (map (partial process-row empty days) selected))))))

;; END READ VACATION SHEET

;; START WRITE REGISTRATION SHEET

(def column-header-values ["Data" "" "Hora Entrada"  "Saida Almo√ßo" "Assinatura" "Entrada Almo√ßo" "Hora Saida" "Assinatura"])

(def start-day "09:00")
(def start-lunch "12:00")
(def end-lunch "13:00")
(def end-day "17:30")

(defn get-month [month]
  (case month
    "Jan" "Janeiro"
    "Feb" "Fevereiro"
    "Mar" "Mar√ßo"
    "Apr" "Abril"
    "May" "Maio"
    "Jun" "Junho"
    "Jul" "Julho"
    "Aug" "Agosto"
    "Sep" "Setembro"
    "Oct" "Outobro"
    "Nov" "Novembro"
    "Dec" "Dezembro"))

(defn get-month-number [month]
  (case month
    "Jan" 1
    "Feb" 2
    "Mar" 3
    "Apr" 4
    "May" 5
    "Jun" 6
    "Jul" 7
    "Aug" 8
    "Sep" 9
    "Oct" 10
    "Nov" 11
    "Dec" 12))

(defn zip-with-index [coll]
  (loop [values coll
         acc {}
         index 0]
    (if (empty? values)
      acc
      (recur (rest values) (assoc acc index (first values)) (+ index 1)))))

(defn by-key [c1]
  (let [[k _] c1]
    k))

(defn create-header-values [name month year]
  ["Nome" name "" "Mes" (get-month month) "" "Ano" year])

(defn generate-day-row [year month day]
  (let [[index status] day
        year-value (Integer/parseInt year)
        month-value (get-month-number month)
        day-value (+ index 1)
        today (LocalDate/now)
        cell-day (LocalDate/of year-value month-value day-value)
        base [day-value ""]]
    (cond
      (.isAfter cell-day today) (conj base "" start-day start-lunch "" end-lunch end-day "")
      (= status :holiday) (conj base "" start-day start-lunch "" end-lunch end-day "")
      (= status :vacation) (conj base "" start-day start-lunch "" end-lunch end-day "")
      (= status :sick) (conj base "" start-day start-lunch "" end-lunch end-day "")
      (= status :full) (conj base "" start-day start-lunch "X" end-lunch end-day "X")
      (= status :half) (conj base "" start-day start-lunch "X" end-lunch end-day ""))))


(defn generate-day-rows [month year schedule]
  (loop [values schedule
         acc []]
    (if (empty? values)
      acc
      (recur (rest values) (conj acc (generate-day-row year month (first values)))))))

(defn generate-content [name month year schedule]
  (let [header (create-header-values name month year)
        days (generate-day-rows month year schedule)
        base [header [] [] column-header-values]]
    (reduce (fn [acc v] (conj acc v)) base days)))


(defn set-row-style [rows]
   (dorun (map #(.setHeightInPoints %1 40) rows)))

(defn set-image-cell [ workbook picture drawing cell]
  (let [value (try (.getStringCellValue cell) (catch Exception e cell))
        row (.getRowIndex cell)
        column (.getColumnIndex cell)
        helper (.getCreationHelper workbook)
        anchor (.createClientAnchor helper)
        _ (.setCol1 anchor column)
        _ (.setRow1 anchor row)]
    (case value
      "X" (do
            (.setCellValue cell "")
            (def pict (.createPicture drawing anchor picture))
            (.resize pict 0.8))
      cell)))

(defn set-images-rows [workbook picture drawing rows]
  (let [cells   (->> rows
                     (map #(iterator-seq (.cellIterator %1)))
                     (flatten)
                     (remove nil?))
        set-image-fn (partial set-image-cell workbook picture drawing)]
    (dorun (map set-image-fn cells))))

(defn write-registration [month year signature-file selected-schedule]
  (let [{name :name days :days} selected-schedule
        with-day (zip-with-index days)
        sorted-with-day (sort-by by-key with-day)
        content (generate-content name month year sorted-with-day)
        sheet-name "Registration"
        workbook (ss/create-workbook sheet-name content)
        sheet (ss/select-sheet sheet-name workbook)
        rows (remove #(= -1 (.getLastCellNum %1)) (ss/row-seq sheet))]
    (with-open [image (io/input-stream signature-file)
                workbook-stream (new java.io.ByteArrayOutputStream)]
      (let [bytes (IOUtils/toByteArray image)
            picture (.addPicture workbook bytes 6)
            drawing (.createDrawingPatriarch sheet)]
        (set-row-style rows)
        (set-images-rows workbook picture drawing rows)
        (ss/save-workbook! workbook-stream workbook)
        workbook-stream))))

;; END WRITE REGISTRATION SHEET


;; START PROCESSING

(defn process [month year name vacation signature]
  (let  [schedule (read-vacation name month vacation)
         selected-schedule (first (filter (fn [{n :name}] (= name n)) schedule))
         workbook-stream (write-registration month year signature selected-schedule)]
    workbook-stream))

;; END PROCESSING

;; START CLI HANDLER

(defn cli-handler [month year name vacation signature output]
  (with-open [processed (io/input-stream (.toByteArray (process month year name vacation signature)))
              output (io/output-stream output)]
    (io/copy processed output)))

;; END CLI HANDLER

;; START REQUEST HANDLER

(defn request-handler [req]
  (let [{{:strs [month year name]} :query-params} req
        {{:strs [vacation signature]} :params} req
        {vacation-file :tempfile} vacation
        {signature-file :tempfile} signature
        processed (process month year name vacation-file signature-file)
        response-ready (io/input-stream (.toByteArray processed))]
    (response response-ready)))

;; END REQUEST HANDLER

(defn -main [runner & args]
  (println (str "Running in " runner " mode!"))
  (case runner
    "cli" (let [[month year name vacation signature output] args]
            (cli-handler month year name vacation signature output))
    "server" (let [port (Integer/parseInt (System/getenv "PORT"))]
               (jetty/run-jetty (-> (partial request-handler)
                                    wrap-params
                                    wrap-multipart-params)
                                {:port port})
               (println (str "Connected to " port)))))