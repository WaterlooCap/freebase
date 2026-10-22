import "metabase/plugins/builtin";
import { screen } from "__support__/ui";

import { setup } from "./setup";

describe("Login", () => {
  it("should disable password login without the 'disable_password_login' token feature", () => {
    setup({
      isPasswordLoginEnabled: false,
      isGoogleAuthEnabled: true,
    });

    expect(screen.queryByText("Sign in with email")).not.toBeInTheDocument();
  });
});
