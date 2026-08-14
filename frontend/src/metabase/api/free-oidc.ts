import type { Settings } from "metabase-types/api";

import { Api } from "./api";
import { invalidateTags, tag } from "./tags";

type FreeOidcSettings = Pick<
  Settings,
  | "free-oidc-enabled"
  | "free-oidc-issuer-uri"
  | "free-oidc-client-id"
  | "free-oidc-client-secret"
  | "free-oidc-scopes"
>;

export const freeOidcApi = Api.injectEndpoints({
  endpoints: (builder) => ({
    updateFreeOidc: builder.mutation<
      { ok: boolean },
      Partial<FreeOidcSettings>
    >({
      query: (settings) => ({
        method: "PUT",
        url: `/api/oidc/settings`,
        body: settings,
      }),
      invalidatesTags: (_, error) =>
        invalidateTags(error, [tag("session-properties")]),
    }),
  }),
});

export const { useUpdateFreeOidcMutation } = freeOidcApi;
