import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

// The whole flow with a customer and an agent in two separate browsers, against the demo company:
// handoff, a live reply, typing, a quoted reply, an image, and starting a new chat.

const CHAT = "/w/demo?pk=pk_test_demo";
// A 1×1 PNG.
const PIXEL = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
  "base64",
);

// Turns images on for the demo company, through the admin API with the demo sign-in.
async function allowImages(request: APIRequestContext) {
  const signIn = await request.post("/dev/agent-token", { data: { sub: "dev-agent", name: "Sam", admin: true } });
  expect(signIn.ok()).toBeTruthy();
  const headers = { Authorization: `Bearer ${(await signIn.json()).accessToken}` };
  const tenant = await (await request.get("/api/v1/admin/tenants/demo", { headers })).json();
  const saved = await request.put("/api/v1/admin/tenants/demo", { headers, data: { ...tenant, attachmentsEnabled: true } });
  expect(saved.ok()).toBeTruthy();
}

function messageBox(page: Page) {
  return page.getByRole("textbox", { name: /message|your email|your name/i });
}

async function say(page: Page, text: string) {
  await messageBox(page).fill(text);
  await messageBox(page).press("Enter");
}

test.beforeAll(async ({ request }) => {
  await allowImages(request);
});

test("a customer and an agent talk in the same conversation", async ({ browser }) => {
  const run = Date.now().toString(36);
  const name = `E2E Pat ${run}`;
  const customer = await (await browser.newContext()).newPage();
  const agent = await (await browser.newContext()).newPage();

  // The customer asks for a person and leaves their email and name.
  await customer.goto(CHAT);
  await customer.getByRole("button", { name: "Talk to a person" }).click();
  await say(customer, `e2e-${run}@example.test`);
  await say(customer, name);
  await expect(customer.getByText(/Connecting you with the team/)).toBeVisible();

  // The agent finds the chat in the queue.
  await agent.goto("/console");
  await agent.getByRole("button", { name: "Sign in as dev agent" }).click();
  await agent.getByText(name).first().click();

  // Typing shows on the customer's side; the reply arrives live (and takes the chat).
  await messageBox(agent).fill("Let me look");
  await expect(customer.getByText("Sam is typing…")).toBeVisible();
  await messageBox(agent).press("Enter");
  await expect(customer.getByText("Let me look")).toBeVisible();

  // The customer replies to that message; the agent sees the quote.
  await customer.getByText("Let me look").hover();
  await customer.getByRole("button", { name: "Reply" }).last().click();
  await expect(customer.getByText("Replying to Sam")).toBeVisible();
  await say(customer, `About that, ${run}`);
  const quoted = agent.locator(".bubble", { hasText: `About that, ${run}` });
  await expect(quoted.locator(".bubble-quote")).toContainText("Let me look");

  // The agent sends an image; the customer sees it.
  await agent.locator('input[type="file"]').setInputFiles({ name: "pixel.png", mimeType: "image/png", buffer: PIXEL });
  await expect(customer.getByAltText("pixel.png")).toBeVisible();

  // The customer starts over: the old chat is gone from their panel, the greeting is back.
  await customer.getByRole("button", { name: "Chat options" }).click();
  await customer.getByRole("button", { name: "Start a new chat" }).click();
  await customer.getByRole("button", { name: "Start over" }).click();
  await expect(customer.getByText("Let me look")).toHaveCount(0);
  await expect(customer.getByRole("button", { name: "Talk to a person" })).toBeVisible();
});
