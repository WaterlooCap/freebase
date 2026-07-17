import { render, screen } from "__support__/ui";

import { OidcButton } from "./OidcButton";

describe("OidcButton", () => {
  it("links to the OIDC SSO initiate route", () => {
    render(<OidcButton />);
    const link = screen.getByRole("link", { name: /sign in with sso/i });
    expect(link).toHaveAttribute("href", "/auth/sso/oidc");
  });

  it("passes the redirect through so users land where they were going", () => {
    render(<OidcButton redirectUrl="/dashboard/1" />);
    const link = screen.getByRole("link", { name: /sign in with sso/i });
    expect(link).toHaveAttribute(
      "href",
      "/auth/sso/oidc?redirect=%2Fdashboard%2F1",
    );
  });
});
