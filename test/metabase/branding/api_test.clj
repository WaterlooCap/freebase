(ns metabase.branding.api-test
  (:require
   [clojure.test :refer :all]
   [metabase.appearance.settings :as appearance.settings]
   [metabase.branding.settings :as branding.settings]
   [metabase.test :as mt]))

(deftest requires-superuser-test
  (testing "non-admins cannot change branding"
    (is (= "You don't have permissions to do that."
           (mt/user-http-request :rasta :put 403 "branding/settings"
                                 {:wc-brand-name "Pwned"})))))

(deftest admin-can-set-branding-test
  (testing "an admin can set the brand name and colors"
    (mt/with-temporary-setting-values [wc-brand-name nil, wc-brand-colors {}]
      (mt/user-http-request :crowberto :put 200 "branding/settings"
                            {:wc-brand-name   "Waterloo"
                             :wc-brand-colors {"brand" "#3E90C5"}})
      (is (= "Waterloo" (branding.settings/wc-brand-name)))
      (is (= {:brand "#3E90C5"} (branding.settings/wc-brand-colors))))))

(deftest closed-schema-drops-unknown-keys-test
  (testing "a superuser cannot smuggle unrelated settings through this endpoint"
    ;; Upstream v0.63.15 added `strip-extra-keys-transformer` to
    ;; `metabase.api.macros/decode-transformer`, so an undeclared key is now dropped while
    ;; decoding rather than rejected by the closed schema -- the request succeeds with the
    ;; extra key discarded instead of failing with a 400. The invariant that matters is
    ;; unchanged, and is what we assert: the undeclared setting never reaches `set-many!`.
    (mt/with-temporary-setting-values [application-name "Metabase"
                                       wc-brand-name    nil]
      (mt/user-http-request :crowberto :put 200 "branding/settings"
                            {:wc-brand-name    "x"
                             :application-name "EVIL"})
      (is (= "x" (branding.settings/wc-brand-name))
          "the declared key is still applied")
      (is (= "Metabase" (appearance.settings/application-name))
          "the undeclared key is stripped before set-many!, so application-name stays unchanged"))))
