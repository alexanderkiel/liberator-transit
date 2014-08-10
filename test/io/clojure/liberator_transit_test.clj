; Copyright © 2014 Daniel Solano Gómez
; All rights reserved.
;
; The use and distribution terms for this software are covered by the Eclipse
; Public License 1.0 <http://www.eclipse.org/legal/epl-v10.html> which can be
; found in the file LICENSE at the root of this distribution.  By using this
; software in any fashion, you are agreeing to be bound by the terms of this
; license.  You must not remove this notice, or any other, from this software.

(ns io.clojure.liberator-transit-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [cognitect.transit :as transit]
            [io.clojure.liberator-transit]
            [io.clojure.liberator-transit.generators :as gen]
            [liberator.core :refer [defresource]]
            [liberator.dev :refer [wrap-trace]]
            [ring.mock.request :as mock]))

(defresource test-resource [value]
  :available-media-types ["application/transit+json" "application/transit+msgpack"]
  :handle-ok value)

(defn make-request
  [type]
  (-> (mock/request :get "/")
      (mock/header "Accept" type)))

(defn json-request
  ([]
   (make-request "application/transit+json"))
  ([_]
   (make-request "application/transit+json;verbose")))

(defn msgpack-request []
  (make-request "application/transit+msgpack"))

(defn to-string
  [{:keys [body]}]
  (slurp body))

(defn to-bytes
  [{:keys [body]}]
  (let [out (java.io.ByteArrayOutputStream. 4096)]
    (io/copy body out)
    (into [] (map #(bit-and % 0x0ff) (.toByteArray out)))))

(defn jsonify
  ([v]
   (jsonify v false))
  ([v verbose?]
   (let [out (java.io.ByteArrayOutputStream. 4096)
         writer (transit/writer out (if verbose? :json-verbose :json))]
     (transit/write writer v)
     (slurp (java.io.ByteArrayInputStream. (.toByteArray out))))))

(defn packify [v]
  (let [out (java.io.ByteArrayOutputStream. 4096)
        writer (transit/writer out :msgpack)]
    (transit/write writer v)
    (into [] (map #(bit-and % 0x0ff) (.toByteArray out)))))

(deftest fixed-sequences
  (testing "Fixed sequeces"
    (testing "JSON encoding"
      (are [json data] (= json (to-string ((test-resource data) (json-request))))
           "[]" []
           "[null]" [nil]
           "[true,false]" [true false]
           "[42,3.1415926]" [42 3.1415926]
           "[\"~:foo\",\"bar\",\"~$baz\"]" [:foo "bar" 'baz]
           "[\"~#list\",[[\"^0\",[]],[]]]" '(() [])
           "[\"~#list\",[1,2,3,4,5]]" (range 1 6)
           "[\"~:foo\",\"~:bar\",\"^0\",\"^1\",\"^0\",\"^1\"]" [:foo :bar :foo :bar :foo :bar]))
    (testing "JSON-verbose encoding"
      (are [json data] (= json (to-string ((test-resource data) (json-request :verbose))))
           "[]" []
           "[null]" [nil]
           "[true,false]" [true false]
           "[42,3.1415926]" [42 3.1415926]
           "[\"~:foo\",\"bar\",\"~$baz\"]" [:foo "bar" 'baz]
           "{\"~#list\":[{\"~#list\":[]},[]]}" '(() [])
           "{\"~#list\":[1,2,3,4,5]}" (range 1 6)
           "[\"~:foo\",\"~:bar\",\"~:foo\",\"~:bar\",\"~:foo\",\"~:bar\"]" [:foo :bar :foo :bar :foo :bar]))
    (testing "messagepack-verbose encoding"
      (are [msgpack data] (= msgpack (to-bytes ((test-resource data) (msgpack-request))))
           [0x90] []
           [0x91 0xc0] [nil]
           [0x92 0xc3 0xc2] [true false]
           [0x92 0x2a 0xcb 0x40 0x09 0x21 0xfb 0x4d 0x12 0xd8 0x4a] [42 3.1415926]
           [0x93 0xa5 0x7e 0x3a 0x66 0x6f 0x6f 0xa3 0x62 0x61 0x72 0xa5 0x7e 0x24 0x62 0x61 0x7a] [:foo "bar" 'baz]
           [0x92 0xa6 0x7e 0x23 0x6c 0x69 0x73 0x74 0x92 0x92 0xa2 0x5e 0x30 0x90 0x90] '(() [])
           [0x92 0xa6 0x7e 0x23 0x6c 0x69 0x73 0x74 0x95 0x01 0x02 0x03 0x04 0x05] (range 1 6)
           [0x96 0xa5 0x7e 0x3a 0x66 0x6f 0x6f 0xa5 0x7e 0x3a 0x62 0x61 0x72 0xa2 0x5e 0x30 0xa2 0x5e 0x31 0xa2 0x5e 0x30 0xa2 0x5e 0x31] [:foo :bar :foo :bar :foo :bar]))))

(deftest fixed-maps
  (testing "Fixed sequeces"
    (testing "JSON encoding"
      (are [json data] (= json (to-string ((test-resource data) (json-request))))
           "[\"^ \"]" {}
           "[\"^ \",\"foo\",1]" {"foo" 1}
           "[\"^ \",\"~:a\",[\"^ \"],\"~:b\",42]" {:a {} :b 42}
           ))
    (testing "JSON-verbose encoding"
      (are [json data] (= json (to-string ((test-resource data) (json-request :verbose))))
           "{}" {}
           "{\"foo\":1}" {"foo" 1}
           "{\"~:a\":{},\"~:b\":42}" {:a {} :b 42}
           ))
    (testing "messagepack-verbose encoding"
      (are [msgpack data] (= msgpack (to-bytes ((test-resource data) (msgpack-request))))
           [0x80] {}
           [0x81 0xa3 0x66 0x6f 0x6f 0x01] {"foo" 1}
           [0x82 0xa3 0x7e 0x3a 0x61 0x80 0xa3 0x7e 0x3a 0x62 0x2a] {:a {} :b 42}))))

(defspec generated-sequences
  80
  (prop/for-all [v gen/sequence-generator]
    (= (jsonify v) (to-string ((test-resource v) (json-request))))
    (= (jsonify v :verbose) (to-string ((test-resource v) (json-request :verbose))))
    (= (packify v) (to-bytes ((test-resource v) (msgpack-request))))))

(defspec generated-maps
  80
  (prop/for-all [v gen/map-generator]
    (= (jsonify v) (to-string ((test-resource v) (json-request))))
    (= (jsonify v :verbose) (to-string ((test-resource v) (json-request :verbose))))
    (= (packify v) (to-bytes ((test-resource v) (msgpack-request))))))
