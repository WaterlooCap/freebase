import { mockSettings } from "__support__/settings";
import { createMockState } from "metabase/redux/store/mocks";

import { getApplicationName } from "./application-name";

function setup(wcBrandName?: string) {
  return createMockState({
    settings: mockSettings(
      wcBrandName === undefined ? {} : { "wc-brand-name": wcBrandName },
    ),
  });
}

describe("getApplicationName (OSS)", () => {
  it("returns the stock name when wc-brand-name is unset", () => {
    const state = setup();

    expect(getApplicationName(state)).toBe("Metabase");
  });

  it("returns the stock name when wc-brand-name is empty", () => {
    const state = setup("");

    expect(getApplicationName(state)).toBe("Metabase");
  });

  it("reads the brand name from wc-brand-name without any token feature", () => {
    // Deliberately no token-features set anywhere in this test: our branding must not
    // depend on a license.
    const state = setup("Waterloo Capital");

    expect(getApplicationName(state)).toBe("Waterloo Capital");
  });
});
