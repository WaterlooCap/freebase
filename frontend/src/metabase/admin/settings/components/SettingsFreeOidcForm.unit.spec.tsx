import userEvent from "@testing-library/user-event";
import fetchMock from "fetch-mock";

import {
  findRequests,
  setupPropertiesEndpoints,
  setupSettingsEndpoints,
} from "__support__/server-mocks";
import { renderWithProviders, screen } from "__support__/ui";
import type { Settings } from "metabase-types/api";
import { createMockSettings } from "metabase-types/api/mocks";

import { SettingsFreeOidcForm } from "./SettingsFreeOidcForm";

const setup = async (settingValues?: Partial<Settings>) => {
  const settings = createMockSettings(settingValues);
  setupSettingsEndpoints([]);
  setupPropertiesEndpoints(settings);

  renderWithProviders(<SettingsFreeOidcForm />);

  await screen.findByLabelText(/Issuer URI/);
};

describe("SettingsFreeOidcForm", () => {
  it("should pre-fill from existing settings", async () => {
    await setup({
      "free-oidc-issuer-uri": "https://sso.example.com/application/o/metabase/",
      "free-oidc-client-id": "existing-client-id",
      "free-oidc-scopes": "openid email",
    });

    expect(
      screen.getByDisplayValue(
        "https://sso.example.com/application/o/metabase/",
      ),
    ).toBeInTheDocument();
    expect(screen.getByDisplayValue("existing-client-id")).toBeInTheDocument();
    expect(screen.getByDisplayValue("openid email")).toBeInTheDocument();
  });

  it("should default scopes to the standard OIDC scopes when unset", async () => {
    await setup();

    expect(
      screen.getByDisplayValue("openid email profile"),
    ).toBeInTheDocument();
  });

  it("should submit the correct payload and enable OIDC", async () => {
    fetchMock.put("path:/api/oidc/settings", { ok: true });
    await setup();

    await userEvent.type(
      screen.getByLabelText(/Issuer URI/),
      "https://sso.example.com/application/o/metabase/",
    );
    await userEvent.type(screen.getByLabelText(/Client ID/), "client-123");
    await userEvent.type(
      screen.getByLabelText(/Client Secret/),
      "super-secret",
    );

    await userEvent.click(await screen.findByRole("button", { name: /Save/ }));

    const [{ url, body }] = await findRequests("PUT");
    expect(url).toMatch(/api\/oidc\/settings/);
    expect(body).toEqual({
      "free-oidc-issuer-uri": "https://sso.example.com/application/o/metabase/",
      "free-oidc-client-id": "client-123",
      "free-oidc-client-secret": "super-secret",
      "free-oidc-scopes": "openid email profile",
      "free-oidc-enabled": true,
    });
  });

  it("should surface a backend probe failure via the form error message", async () => {
    fetchMock.put("path:/api/oidc/settings", {
      status: 400,
      body: {
        errors: {
          discovery: {
            step: "discovery",
            success: false,
            error:
              "Could not fetch OIDC discovery document from https://bad.example.com",
          },
        },
      },
    });

    await setup();

    await userEvent.type(
      screen.getByLabelText(/Issuer URI/),
      "https://bad.example.com",
    );
    await userEvent.type(screen.getByLabelText(/Client ID/), "client-123");
    await userEvent.type(
      screen.getByLabelText(/Client Secret/),
      "super-secret",
    );

    await userEvent.click(await screen.findByRole("button", { name: /Save/ }));

    expect(
      await screen.findByText(/Could not fetch OIDC discovery document/),
    ).toBeInTheDocument();
  });

  it("should show a disable action once OIDC is enabled", async () => {
    await setup({ "free-oidc-enabled": true });

    expect(screen.getByRole("button", { name: /Disable/ })).toBeInTheDocument();
  });
});
