import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, type AdminApi, type AdminTenant } from "../../lib/api";
import { KnowledgeCard } from "./KnowledgeCard";

const tenant = { slug: "acme", displayName: "Acme" } as AdminTenant;
const shipping = { id: "d1", title: "Shipping", length: 2400, chunks: 3, updatedBy: "sam", updatedAt: "2026-11-02T10:00:00Z" };

function fakeApi(overrides: Partial<AdminApi> = {}) {
  return {
    knowledge: vi.fn(() => Promise.resolve({ items: [shipping] })),
    document: vi.fn(() => Promise.resolve({ ...shipping, body: "# Shipping\nTwo days." })),
    addDocument: vi.fn(() => Promise.resolve({ ...shipping, body: "" })),
    updateDocument: vi.fn(() => Promise.resolve({ ...shipping, body: "" })),
    removeDocument: vi.fn(() => Promise.resolve()),
    ...overrides,
  } as unknown as AdminApi;
}

afterEach(cleanup);

describe("KnowledgeCard", () => {
  it("lists documents with their size and parts", async () => {
    render(<KnowledgeCard api={fakeApi()} tenant={tenant} />);
    expect(await screen.findByText("Shipping")).toBeTruthy();
    expect(screen.getByText("2k characters, 3 parts")).toBeTruthy();
  });

  it("adds a document from a file, titled after the file", async () => {
    const api = fakeApi();
    const { container } = render(<KnowledgeCard api={api} tenant={tenant} />);
    await screen.findByText("Shipping");

    const file = new File(["# Returns\n30 days."], "returns.md", { type: "text/markdown" });
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [file] } });
    await waitFor(() => expect(screen.getByDisplayValue("returns")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Add document" }));

    await waitFor(() =>
      expect(api.addDocument).toHaveBeenCalledWith("acme", { title: "returns", body: "# Returns\n30 days." }),
    );
    expect(api.knowledge).toHaveBeenCalledTimes(2);
  });

  it("edits an existing document", async () => {
    const api = fakeApi();
    render(<KnowledgeCard api={api} tenant={tenant} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Save document" })).toBeTruthy());
    fireEvent.change(screen.getByLabelText("Text or Markdown"), { target: { value: "One day now." } });
    fireEvent.click(screen.getByRole("button", { name: "Save document" }));

    await waitFor(() =>
      expect(api.updateDocument).toHaveBeenCalledWith("acme", "d1", { title: "Shipping", body: "One day now." }),
    );
  });

  it("says when the company has too many documents", async () => {
    const api = fakeApi({
      addDocument: vi.fn(() => Promise.reject(new ApiError(409, "TOO_MANY_DOCUMENTS", "A company can have up to 200 documents"))),
    });
    render(<KnowledgeCard api={api} tenant={tenant} />);
    await screen.findByText("Shipping");
    fireEvent.change(screen.getByPlaceholderText("Shipping and returns"), { target: { value: "More" } });
    fireEvent.change(screen.getByLabelText("Text or Markdown"), { target: { value: "Text" } });
    fireEvent.click(screen.getByRole("button", { name: "Add document" }));

    expect(await screen.findByText("A company can have up to 200 documents")).toBeTruthy();
  });
});
