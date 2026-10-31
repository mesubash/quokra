import { defineConfig, devices } from "@playwright/test";

// End-to-end tests against a running Quokra with the demo data and the demo sign-in
// (see e2e/README.md). E2E_BASE_URL points elsewhere if it isn't on localhost:8080.
export default defineConfig({
  testDir: "tests",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:8080",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
