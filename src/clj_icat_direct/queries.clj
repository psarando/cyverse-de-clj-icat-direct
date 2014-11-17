(ns clj-icat-direct.queries
  (:require [clojure.string :as string])
  (:import  [clojure.lang IPersistentMap ISeq]))


(defn- bad-chars->sql-char-class
  [bad-chars]
  (let [bad-chars (string/replace bad-chars #"'|\[|\]|\\" {"\\" "\\\\"
                                                           "'"  "\\'"
                                                           "["  "\\["
                                                           "]"  "\\]"})]
    (str "[" bad-chars "]")))


(defn- file-bad-chars-cond
  [_ bad-chars]
  (str "d.data_name ~ E'" (bad-chars->sql-char-class bad-chars) "'"))


(defn- folder-bad-chars-cond
  [parent-path bad-chars]
  (str "c.coll_name ~ E'" parent-path "/.*" (bad-chars->sql-char-class bad-chars) "'"))


(defn- file-name-cond
  [_ name]
  (str "d.data_name = '" name "'"))


(defn- file-path-cond
  [parent-path path]
  (str "'" parent-path "' || '/' || d.data_name = '" path "'"))


(defn- folder-name-cond
  [parent-path name]
  (str "c.coll_name = '" parent-path "/" name "'"))


(defn- folder-path-cond
  [_ path]
  (str "c.coll_name = '" path "'"))


(defn- mk-bad-cond
  [mk-bad-chars-cond mk-bad-name-cond mk-bad-path-cond parent-path bad-chars bad-names bad-paths]
  (let [conds (concat (when-not (empty? bad-chars) [(mk-bad-chars-cond parent-path bad-chars)])
                      (map #(mk-bad-name-cond parent-path %) bad-names)
                      (map #(mk-bad-path-cond parent-path %) bad-paths))
        conds (remove nil? conds)]
    (if (empty? conds)
      "FALSE"
      (string/join " OR " (map #(str "(" % ")") conds)))))


(defn ^String mk-bad-file-cond
  "Returns a WHERE condition for files in the count-bad-items-in-folder query.

   Parameters:
     parent-path - the absolute path to the folder containing the files
     bad-chars   - the characters a name cannot contain
     bad-names   - the bad names
     bad-paths   - the bad paths

   Returns:
     It returns the condition."
  [^String parent-path ^String bad-chars ^ISeq bad-names ^ISeq bad-paths]
  (mk-bad-cond file-bad-chars-cond file-name-cond file-path-cond
               parent-path bad-chars bad-names bad-paths))


(defn ^String mk-bad-folder-cond
  "Returns a WHERE condition for folders in the count-bad-items-in-folder query.

   Parameters:
     parent-path - the absolute path to the folder containing the folders
     bad-chars   - the characters a name cannot contain
     bad-names   - the bad names
     bad-paths   - the bad paths

   Returns:
     It returns the condition."
  [^String parent-path ^String bad-chars ^ISeq bad-names ^ISeq bad-paths]
  (mk-bad-cond folder-bad-chars-cond folder-name-cond folder-path-cond
                  parent-path bad-chars bad-names bad-paths))


(defn prepare-text-set
  "Given a set, it prepares the elements for injection into an SQL query. It returns a string
   containing the quoted values separated by commas."
  [values]
  (string/join ", " (map #(str \' % \') values)))


(defn ^String mk-file-type-cond
  "This function constructs condition for search for files with a given file type.

   Parameters:
     file-types - A list of file types used to filter on. A nil or empty list disables filtering.

   Returns:
     It returns a condition for filtering data objects by file type."
  [^ISeq file-types]
  (let [file-types (mapv string/lower-case file-types)
        fmt-ft     (prepare-text-set file-types)]
    (cond
      (empty? file-types)
      "TRUE"

      (some #(= "raw" %) file-types)
      (str "f.meta_attr_value IS NULL OR f.meta_attr_value IN ('', " fmt-ft ")")

      :else
      (str "f.meta_attr_value IN (" fmt-ft ")"))))


(def ^IPersistentMap sort-columns
  {:type      "type"
   :modify-ts "modify_ts"
   :create-ts "create_ts"
   :data-size "data_size"
   :base-name "base_name"
   :full-path "full_path"})


(def ^IPersistentMap sort-directions
  {:asc "ASC" :desc "DESC"})


(defn- mk-unique-objs-in-coll-query
  [parent-path]
  (str "SELECT *
          FROM r_data_main AS d1
          WHERE coll_id = (SELECT coll_id FROM r_coll_main WHERE coll_name = '" parent-path "')
            AND d1.data_repl_num = (SELECT MIN(d2.data_repl_num)
                                      FROM r_data_main AS d2
                                      WHERE d2.data_id = d1.data_id)"))


(defn- mk-obj-avus-query
  [file-ids-query]
  (str "SELECT *
          FROM r_objt_metamap AS o JOIN r_meta_main AS m ON o.meta_id = m.meta_id
          WHERE o.object_id = ANY(ARRAY(" file-ids-query "))"))


(defn- mk-file-types-query
  [avus-cte]
  (str "SELECT * FROM " avus-cte " WHERE meta_attr_name = 'ipc-filetype'"))


(defn- mk-folder-files-query
  [parent-path group-ids-query info-type-cond data-objs-cte avus-cte]
  (str "SELECT 'dataobject'                      AS type,
               m.meta_attr_value                 AS uuid,
               '" parent-path "/' || d.data_name AS full_path,
               d.data_name                       AS base_name,
               f.meta_attr_value                 AS info_type,
               d.data_size                       AS data_size,
               d.create_ts                       AS create_ts,
               d.modify_ts                       AS modify_ts,
               MAX(a.access_type_id)             AS access_type_id
          FROM " data-objs-cte " AS d
            JOIN " avus-cte " AS m ON d.data_id = m.object_id
            JOIN r_objt_access AS a ON d.data_id = a.object_id
            LEFT JOIN (" (mk-file-types-query avus-cte) ") AS f
              ON d.data_id = f.object_id
          WHERE a.user_id IN (" group-ids-query ")
            AND m.meta_attr_name = 'ipc_UUID'
            AND (" info-type-cond ")
          GROUP BY type, uuid, full_path, base_name, info_type, data_size, d.create_ts,
                   d.modify_ts"))


(defn- mk-folder-folders-query
  [parent-path group-ids-query]
  (str "SELECT 'collection'                           AS type,
               m.meta_attr_value                      AS uuid,
               c.coll_name                            AS full_path,
               REGEXP_REPLACE(c.coll_name, '.*/', '') AS base_name,
               NULL                                   AS info_type,
               0                                      AS data_size,
               c.create_ts                            AS create_ts,
               c.modify_ts                            AS modify_ts,
               MAX(a.access_type_id)                  AS access_type_id
          FROM r_coll_main AS c
            JOIN r_objt_metamap AS om ON om.object_id = c.coll_id
            JOIN r_meta_main AS m ON m.meta_id = om.meta_id
            JOIN r_objt_access AS a ON c.coll_id = a.object_id
          WHERE c.parent_coll_name = '" parent-path "'
            AND c.coll_type != 'linkPoint'
            AND m.meta_attr_name = 'ipc_UUID'
            AND a.user_id IN (" group-ids-query ")
          GROUP BY type, uuid, full_path, base_name, info_type, data_size, c.create_ts,
                   c.modify_ts"))


(defn- mk-groups-query
  [user zone]
  (str "SELECT *
          FROM r_user_group
          WHERE user_id IN (SELECT user_id
                              FROM r_user_main
                              WHERE user_name = '" user "' AND zone_name = '" zone "')"))


(defn- mk-count-colls-in-coll
  [parent-path group-ids-query]
  (str "SELECT COUNT(*) AS total
          FROM r_coll_main c JOIN r_objt_access AS a ON c.coll_id = a.object_id
          WHERE c.parent_coll_name = '" parent-path "'
            AND c.coll_type != 'linkPoint'
            AND a.user_id IN (" group-ids-query ")"))


(defn- mk-count-objs-of-type
  [avus-cte group-query info-type-cond]
  (str "SELECT COUNT(*) AS total
          FROM data_objs AS d
            JOIN r_objt_access AS a ON a.object_id = d.data_id
            LEFT JOIN (" (mk-file-types-query avus-cte) ") AS f ON f.object_id = d.data_id
          WHERE a.user_id IN (" group-query ") AND (" info-type-cond ")"))


(defn ^String mk-count-bad-items-in-folder
  [& {:keys [user zone parent-path info-type-cond bad-file-cond bad-folder-cond]}]
  (str "WITH groups    AS (" (mk-groups-query user zone) "),
             data_objs AS (" (mk-unique-objs-in-coll-query parent-path) "),
             file_avus AS (" (mk-obj-avus-query "SELECT data_id FROM data_objs") ")
        SELECT ((SELECT COUNT(*)
                   FROM r_coll_main c JOIN r_objt_access AS a ON c.coll_id = a.object_id
                   WHERE c.parent_coll_name = '" parent-path "'
                     AND c.coll_type != 'linkPoint'
                     AND a.user_id IN (SELECT group_user_id FROM groups)
                     AND (" bad-folder-cond "))
                +
                (SELECT COUNT(*)
                   FROM data_objs AS d
                     JOIN r_objt_access AS a ON a.object_id = d.data_id
                     LEFT JOIN (SELECT * FROM file_avus WHERE meta_attr_name = 'ipc-filetype') AS f
                       ON f.object_id = d.data_id
                   WHERE a.user_id IN (SELECT group_user_id FROM groups)
                     AND (" info-type-cond ")
                     AND (" bad-file-cond ")))
               AS total"))


(defn ^String mk-count-files-in-folder-query
  [^String user ^String zone ^String parent-path ^String info-type-cond]
  (let [group-query "SELECT group_user_id FROM groups"]
    (str "WITH groups    AS (" (mk-groups-query user zone) "),
               data_objs AS (" (mk-unique-objs-in-coll-query parent-path ) "),
               file_avus AS (" (mk-obj-avus-query "SELECT data_id FROM data_objs") ")
         " (mk-count-objs-of-type "file_avus" group-query info-type-cond))))


(defn ^String mk-count-folders-in-folder-query
  [^String user ^String zone ^String parent-path]
  (let [group-query "SELECT group_user_id FROM groups"]
    (str "WITH groups AS (" (mk-groups-query user zone) ")
         " (mk-count-colls-in-coll parent-path group-query))))


(defn ^String mk-count-items-in-folder-query
  [^String user ^String zone ^String parent-path ^String info-type-cond]
  (let [group-query   "SELECT group_user_id FROM groups"
        folders-query (mk-count-colls-in-coll parent-path group-query)
        files-query   (mk-count-objs-of-type "file_avus" group-query info-type-cond)]
    (str "WITH groups    AS (" (mk-groups-query user zone) "),
               data_objs AS (" (mk-unique-objs-in-coll-query parent-path ) "),
               file_avus AS (" (mk-obj-avus-query "SELECT data_id FROM data_objs") ")
          SELECT ((" folders-query ") + (" files-query ")) AS total")))


(defn ^String mk-paged-folder-files-query
  [& {:keys [user zone parent-path info-type-cond sort-column sort-direction]}]
  (let [group-query "SELECT group_user_id FROM groups"]
    (str "WITH groups    AS (" (mk-groups-query user zone) "),
               data_objs AS (" (mk-unique-objs-in-coll-query parent-path) "),
               file_avus AS (" (mk-obj-avus-query "SELECT data_id FROM data_objs") ")
         " (mk-folder-files-query parent-path group-query info-type-cond "data_objs" "file_avus") "
           ORDER BY type ASC, " sort-column " " sort-direction "
           LIMIT ?
           OFFSET ?")))


(defn ^String mk-paged-folder-folders-query
  [& {:keys [user zone parent-path sort-column sort-direction]}]
  (str "WITH groups AS (" (mk-groups-query user zone) ")
       " (mk-folder-folders-query parent-path "SELECT group_user_id FROM groups") "
        ORDER BY type ASC, " sort-column " " sort-direction "
        LIMIT ?
        OFFSET ?"))


(defn ^String mk-paged-folder-query
  [& {:keys [user zone parent-path info-type-cond sort-column sort-direction]}]
  (let [group-query   "SELECT group_user_id FROM groups"
        folders-query (mk-folder-folders-query parent-path group-query)
        files-query   (mk-folder-files-query parent-path group-query info-type-cond "data_objs"
                                             "file_avus")]
    (str "WITH groups    AS (" (mk-groups-query user zone) "),
               data_objs AS (" (mk-unique-objs-in-coll-query parent-path) "),
               file_avus AS (" (mk-obj-avus-query "SELECT data_id FROM data_objs") ")
          SELECT *
            FROM (" folders-query " UNION " files-query ") AS t
            ORDER BY type ASC, " sort-column " " sort-direction "
            LIMIT ?
            OFFSET ?")))


(def queries
  {:count-all-items-under-folder
   "WITH user_groups AS ( SELECT g.*
                            FROM r_user_main u
                            JOIN r_user_group g ON g.user_id = u.user_id
                           WHERE u.user_name = ?
                             AND u.zone_name = ? ),

         parent      AS ( SELECT * from r_coll_main
                           WHERE coll_name = ?
                          UNION
                          SELECT * from r_coll_main
                           WHERE coll_name LIKE ? || '/%' ),

         data_objs   AS ( SELECT *
                            FROM r_data_main
                           WHERE coll_id = ANY(ARRAY( SELECT coll_id FROM parent )))

    SELECT count(*) AS total
      FROM ( SELECT DISTINCT d.data_id FROM r_objt_access a
               JOIN data_objs d ON a.object_id = d.data_id
              WHERE a.user_id IN ( SELECT group_user_id FROM user_groups )
                AND a.object_id IN ( SELECT data_id from data_objs )
              UNION
             SELECT DISTINCT c.coll_id FROM r_coll_main c
               JOIN r_objt_access a ON c.coll_id = a.object_id
               JOIN parent p ON c.parent_coll_name = p.coll_name
              WHERE a.user_id IN ( SELECT group_user_id FROM user_groups )
                AND c.coll_type != 'linkPoint' ) AS contents"

   :list-folders-in-folder
   "WITH user_groups AS ( SELECT g.* FROM r_user_main u
                            JOIN r_user_group g ON g.user_id = u.user_id
                           WHERE u.user_name = ?
                             AND u.zone_name = ? ),

         parent      AS ( SELECT * from r_coll_main
                           WHERE coll_name = ? )

    SELECT DISTINCT
           c.parent_coll_name                     as dir_name,
           c.coll_name                            as full_path,
           regexp_replace(c.coll_name, '.*/', '') as base_name,
           c.create_ts                            as create_ts,
           c.modify_ts                            as modify_ts,
           'collection'                           as type,
           0                                      as data_size,
           m.meta_attr_value                      as uuid,
           MAX(a.access_type_id)                  as access_type_id
      FROM r_coll_main c
      JOIN r_objt_access a ON c.coll_id = a.object_id
      JOIN parent p ON c.parent_coll_name = p.coll_name
      JOIN r_objt_metamap mm ON mm.object_id = c.coll_id
      JOIN r_meta_main m ON m.meta_id = mm.meta_id 
     WHERE a.user_id IN ( SELECT group_user_id FROM user_groups )
       AND c.coll_type != 'linkPoint'
       AND m.meta_attr_name = 'ipc_UUID'
  GROUP BY dir_name, full_path, base_name, c.create_ts, c.modify_ts, type, data_size, uuid
  ORDER BY base_name ASC"

   :count-files-in-folder
   "WITH user_groups AS ( SELECT g.*
                            FROM r_user_main u
                            JOIN r_user_group g ON g.user_id = u.user_id
                           WHERE u.user_name = ?
                             AND u.zone_name = ? ),

         parent      AS ( SELECT * from r_coll_main
                           WHERE coll_name = ? ),

         data_objs   AS ( SELECT *
                            FROM r_data_main
                           WHERE coll_id = ANY(ARRAY( SELECT coll_id FROM parent )))

      SELECT count(DISTINCT d.data_id) FROM r_objt_access a
        JOIN data_objs d ON a.object_id = d.data_id
       WHERE a.user_id IN ( SELECT group_user_id FROM user_groups )
         AND a.object_id IN ( SELECT data_id from data_objs )"

   :count-folders-in-folder
   "WITH user_groups AS ( SELECT g.*
                            FROM r_user_main u
                            JOIN r_user_group g ON g.user_id = u.user_id
                           WHERE u.user_name = ?
                             AND u.zone_name = ? ),

         parent      AS ( SELECT * from r_coll_main
                           WHERE coll_name = ? )

    SELECT count(DISTINCT c.coll_id) FROM r_coll_main c
      JOIN r_objt_access a ON c.coll_id = a.object_id
      JOIN parent p ON c.parent_coll_name = p.coll_name
     WHERE a.user_id IN ( SELECT group_user_id FROM user_groups )
       AND c.coll_type != 'linkPoint'"

   :file-permissions
   "SELECT DISTINCT o.access_type_id, u.user_name
      FROM r_user_main u,
           r_data_main d,
           r_coll_main c,
           r_tokn_main t,
           r_objt_access o
     WHERE c.coll_name = ?
       AND d.data_name = ?
       AND c.coll_id = d.coll_id
       AND o.object_id = d.data_id
       AND t.token_namespace = 'access_type'
       AND u.user_id = o.user_id
       AND o.access_type_id = t.token_id
     LIMIT ?
    OFFSET ?"

   :folder-permissions
   "SELECT DISTINCT a.access_type_id, u.user_name
     FROM r_coll_main c
     JOIN r_objt_access a ON c.coll_id = a.object_id
     JOIN r_user_main u ON a.user_id = u.user_id
    WHERE c.parent_coll_name = ?
      AND c.coll_name = ?
    LIMIT ?
   OFFSET ?"

   :folder-permissions-for-user
   "WITH user_lookup AS ( SELECT u.user_id as user_id FROM r_user_main u WHERE u.user_name = ?)
    SELECT DISTINCT a.access_type_id
      FROM r_coll_main c
      JOIN r_objt_access a ON c.coll_id = a.object_id
      JOIN r_user_main u ON a.user_id = u.user_id
     WHERE c.coll_name = ?
       AND u.user_id IN ( SELECT g.group_user_id
                           FROM  r_user_group g,
                                 user_lookup
                           WHERE g.user_id = user_lookup.user_id )"

   :file-permissions-for-user
   "WITH user_lookup AS ( SELECT u.user_id as user_id FROM r_user_main u WHERE u.user_name = ? ),
              parent AS ( SELECT c.coll_id as coll_id, c.coll_name as coll_name FROM r_coll_main c WHERE c.coll_name = ? )
    SELECT DISTINCT a.access_type_id
      FROM r_data_main d
      JOIN r_coll_main c ON c.coll_id = d.coll_id
      JOIN r_objt_access a ON d.data_id = a.object_id
      JOIN r_user_main u ON a.user_id = u.user_id,
           user_lookup,
           parent
     WHERE u.user_id IN ( SELECT g.group_user_id
                           FROM  r_user_group g,
                                 user_lookup
                           WHERE g.user_id = user_lookup.user_id )
       AND c.coll_id = parent.coll_id
       AND d.data_name = ?"

   :folder-listing
   "WITH user_access AS (SELECT *
                           FROM r_objt_access
                           WHERE user_id IN (SELECT g.group_user_id
                                               FROM r_user_main u
                                                 JOIN r_user_group g ON g.user_id = u.user_id
                                               WHERE u.user_name = ? AND u.zone_name = ?)),
         parent      AS (SELECT * FROM r_coll_main WHERE coll_name = ?)
    SELECT DISTINCT c.coll_name || '/' || d.data_name AS full_path
      FROM r_data_main d JOIN r_coll_main c ON d.coll_id = c.coll_id
      WHERE c.coll_name IN (SELECT coll_name FROM parent)
        AND d.data_id IN (SELECT object_id FROM user_access)
    UNION
    SELECT coll_name AS full_path
      FROM r_coll_main
      WHERE parent_coll_name IN (SELECT coll_name FROM parent)
        AND coll_id IN (SELECT object_id FROM user_access)
        AND coll_type != 'linkPoint'"

   :select-files-with-uuids
   "SELECT DISTINCT m.meta_attr_value                   uuid,
                    (c.coll_name || '/' || d.data_name) path,
                    1000 * CAST(d.create_ts AS BIGINT)  \"date-created\",
                    1000 * CAST(d.modify_ts AS BIGINT)  \"date-modified\",
                    d.data_size                         \"file-size\"
      FROM r_meta_main m
        JOIN r_objt_metamap o ON m.meta_id = o.meta_id
        JOIN r_data_main d ON o.object_id = d.data_id
        JOIN r_coll_main c ON d.coll_id = c.coll_id
      WHERE m.meta_attr_name = 'ipc_UUID' AND m.meta_attr_value IN (%s)"

   :select-folders-with-uuids
   "SELECT m.meta_attr_value                  uuid,
           c.coll_name                        path,
           1000 * CAST(c.create_ts AS BIGINT) \"date-created\",
           1000 * CAST(c.modify_ts AS BIGINT) \"date-modified\"
      FROM r_meta_main m
        JOIN r_objt_metamap o ON m.meta_id = o.meta_id
        JOIN r_coll_main c ON o.object_id = c.coll_id
      WHERE m.meta_attr_name = 'ipc_UUID' AND m.meta_attr_value IN (%s)"

   :paged-uuid-listing
   "WITH groups AS (SELECT group_user_id
                      FROM r_user_group
                      WHERE user_id IN (SELECT user_id
                                          FROM r_user_main
                                          WHERE user_name = ? AND zone_name = ?)),
         uuids AS (SELECT m.meta_attr_value uuid,
                          o.object_id
                     FROM r_meta_main m JOIN r_objt_metamap o ON m.meta_id = o.meta_id
                     WHERE m.meta_attr_name = 'ipc_UUID'
                       AND m.meta_attr_value IN (%s)
                       AND o.object_id IN (SELECT object_id
                                             FROM r_objt_access
                                             WHERE user_id in (SELECT group_user_id FROM groups))),
         file_types AS (SELECT *
                          FROM r_objt_metamap AS om
                            JOIN r_meta_main AS mm ON mm.meta_id = om.meta_id
                          WHERE om.object_id = ANY(ARRAY(SELECT object_id FROM uuids))
                            AND mm.meta_attr_name = 'ipc-filetype')
    SELECT p.type,
           p.uuid,
           p.full_path,
           p.base_name,
           p.info_type,
           p.data_size,
           p.create_ts,
           p.modify_ts,
           MAX(p.access_type_id) AS access_type_id
      FROM (SELECT 'collection'                           AS type,
                   u.uuid                                 AS uuid,
                   c.coll_name                            AS full_path,
                   regexp_replace(c.coll_name, '.*/', '') AS base_name,
                   NULL                                   AS info_type,
                   0                                      AS data_size,
                   c.create_ts                            AS create_ts,
                   c.modify_ts                            AS modify_ts,
                   a.access_type_id                       AS access_type_id
              FROM uuids u
                JOIN r_coll_main c ON u.object_id = c.coll_id
                JOIN r_objt_access AS a ON c.coll_id = a.object_id
              WHERE c.coll_type != 'linkPoint' AND a.user_id IN (SELECT group_user_id FROM groups)
            UNION
            SELECT 'dataobject'                         AS type,
                   u.uuid                               AS uuid,
                   (c.coll_name || '/' || d1.data_name) AS full_path,
                   d1.data_name                         AS base_name,
                   f.meta_attr_value                    AS info_type,
                   d1.data_size                         AS data_size,
                   d1.create_ts                         AS create_ts,
                   d1.modify_ts                         AS modify_ts,
                   a.access_type_id                     AS access_type_id
              FROM uuids u
                JOIN r_data_main AS d1 ON u.object_id = d1.data_id
                JOIN r_coll_main c ON d1.coll_id = c.coll_id
                JOIN r_objt_access AS a ON d1.data_id = a.object_id
                LEFT JOIN file_types AS f ON d1.data_id = f.object_id
              WHERE d1.data_repl_num = (SELECT MIN(d2.data_repl_num)
                                          FROM r_data_main AS d2
                                          WHERE d2.data_id = d1.data_id)
                AND a.user_id IN (SELECT group_user_id FROM groups)
                AND (%s)) AS p
      GROUP BY p.type, p.uuid, p.full_path, p.base_name, p.info_type, p.data_size, p.create_ts,
               p.modify_ts
      ORDER BY p.type ASC, %s %s
      LIMIT ?
      OFFSET ?"

   :count-uuids-of-file-type
   "WITH groups AS (SELECT *
                      FROM r_user_group
                      WHERE user_id IN (SELECT user_id
                                          FROM r_user_main
                                          WHERE user_name = ? AND zone_name = ?)),
         uuids AS (SELECT m.meta_attr_value AS uuid, o.object_id
                     FROM r_meta_main m JOIN r_objt_metamap o ON m.meta_id = o.meta_id
                     WHERE m.meta_attr_name = 'ipc_UUID'
                       AND m.meta_attr_value IN (%s)
                       AND o.object_id IN (SELECT object_id
                                             FROM r_objt_access
                                             WHERE user_id in (SELECT group_user_id FROM groups))),
         file_types AS (SELECT *
                          FROM r_objt_metamap AS om
                            JOIN r_meta_main AS mm ON mm.meta_id = om.meta_id
                          WHERE om.object_id = ANY(ARRAY(SELECT object_id FROM uuids))
                            AND mm.meta_attr_name = 'ipc-filetype')
    SELECT COUNT(*) AS total
      FROM uuids
      WHERE object_id IN (SELECT coll_id FROM r_coll_main WHERE coll_type != 'linkPoint'
                          UNION
                          SELECT d.data_id
                            FROM r_data_main AS d
                              LEFT JOIN file_types AS f on d.data_id = f.object_id
                            WHERE (%s))"})
