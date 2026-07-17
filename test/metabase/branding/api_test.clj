(ns metabase.branding.api-test
  (:require
   [clojure.test :refer :all]
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
