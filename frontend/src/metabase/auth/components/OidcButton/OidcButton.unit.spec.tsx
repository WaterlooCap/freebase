import "metabase/plugins/builtin";
import { mockSettings } from "__support__/settings";
import { renderWithProviders, screen } from "__support__/ui";
import { createMockState } from "metabase/redux/store/mocks";

import { OidcButton } from "./OidcButton";

const setup = (props: { isCard?: boolean; redirectUrl?: string } = {}) => {
  renderWithProviders(<OidcButton {...props} />, {
    storeInitialState: createMockState({
      settings: mockSettings({ "wc-brand-name": "Waterloo" }),
    }),
  });
};

describe("OidcButton", () => {
  it("links to the OIDC SSO initiate route", () => {
    setup();
    const link = screen.getByRole("link", {
      name: "Sign in with Waterloo SSO",
    });
    expect(link).toHaveAttribute("href", "/auth/sso/oidc");
  });

  it("passes the redirect through so users land where they were going", () => {
    setup({ redirectUrl: "/dashboard/1" });
    const link = screen.getByRole("link", {
      name: "Sign in with Waterloo SSO",
    });
    expect(link).toHaveAttribute(
      "href",
      "/auth/sso/oidc?redirect=%2Fdashboard%2F1",
    );
  });

  it("renders the login page's primary action as a real link to the server route", () => {
    setup({ isCard: true, redirectUrl: "/question/7" });
    const link = screen.getByRole("link", {
      name: "Sign in with Waterloo SSO",
    });
    expect(link.tagName).toBe("A");
    expect(link).toHaveAttribute(
      "href",
      "/auth/sso/oidc?redirect=%2Fquestion%2F7",
    );
  });
});
