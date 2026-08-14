// Register our wc-brand-name override for PLUGIN_SELECTORS.getApplicationName, as
// metabase/plugins/builtin does in the real app.
import "metabase/plugins/builtin/branding/application-name";

import { renderWithProviders, screen } from "__support__/ui";
import {
  createMockSettingsState,
  createMockState,
} from "metabase/redux/store/mocks";

import { WelcomePage } from "./WelcomePage";

const setup = (settings: Record<string, unknown> = {}) => {
  const state = createMockState({
    settings: createMockSettingsState({
      "available-locales": [["en", "English"]],
      ...settings,
    }),
  });

  renderWithProviders(<WelcomePage />, { storeInitialState: state });
};

describe("WelcomePage", () => {
  it("should render before the timeout when the locale is loaded", async () => {
    setup();

    expect(screen.queryByText("Welcome to Metabase")).not.toBeInTheDocument();
    expect(await screen.findByText("Welcome to Metabase")).toBeInTheDocument();
  });

  it("should use the wc-brand-name for the welcome title when set", async () => {
    setup({ "wc-brand-name": "Waterloo" });

    expect(await screen.findByText("Welcome to Waterloo")).toBeInTheDocument();
    expect(screen.queryByText("Welcome to Metabase")).not.toBeInTheDocument();
  });
});
