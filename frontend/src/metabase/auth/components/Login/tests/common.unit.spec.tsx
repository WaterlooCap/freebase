import "metabase/plugins/builtin";
import { screen } from "__support__/ui";

import { setup } from "./setup";

describe("Login", () => {
  // This test must run before any other test that renders the provider list: React
  // reports a missing-key warning only on the first render of a given component.
  it("should render the provider list without React key warnings", () => {
    const errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});

    try {
      setup({ isPasswordLoginEnabled: true, isGoogleAuthEnabled: true });

      const keyWarnings = errorSpy.mock.calls.filter((args) =>
        args.some(
          (arg) => typeof arg === "string" && arg.includes('unique "key" prop'),
        ),
      );
      expect(keyWarnings).toEqual([]);
    } finally {
      errorSpy.mockRestore();
    }
  });

  it("should render a list of auth providers", () => {
    setup({ isPasswordLoginEnabled: true, isGoogleAuthEnabled: true });

    expect(screen.getAllByRole("link")).toHaveLength(2);
  });

  it("should render the panel of the selected provider", () => {
    setup({
      initialRoute: "/auth/login/password",
      isPasswordLoginEnabled: true,
      isGoogleAuthEnabled: true,
    });

    expect(screen.getByRole("button")).toBeInTheDocument();
  });

  it("should implicitly select the only provider with a panel", () => {
    setup({
      isPasswordLoginEnabled: true,
      isGoogleAuthEnabled: false,
    });

    expect(screen.getByRole("button")).toBeInTheDocument();
  });

  it("should not disable password login for OSS", () => {
    setup({ isPasswordLoginEnabled: false, isGoogleAuthEnabled: true });

    expect(screen.getByText("Sign in with email")).toBeInTheDocument();
  });
});
