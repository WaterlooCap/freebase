describe("whitelabel colors", () => {
  const originalBootstrap = window.MetabaseBootstrap;

  afterEach(() => {
    window.MetabaseBootstrap = originalBootstrap;
    jest.resetModules();
  });

  it("reads brand colors from wc-brand-colors without any token feature", async () => {
    window.MetabaseBootstrap = {
      // Deliberately NO token-features: our branding must not depend on a license.
      "wc-brand-colors": { brand: "#3E90C5" },
    } as any;

    const { colors } = await import("./colors");
    expect(colors.brand).toBe("#3E90C5");
  });

  it("falls back to stock colors when wc-brand-colors is unset", async () => {
    window.MetabaseBootstrap = {} as any;

    const { colors } = await import("./colors");
    expect(colors.brand).not.toBe("#3E90C5");
  });
});
