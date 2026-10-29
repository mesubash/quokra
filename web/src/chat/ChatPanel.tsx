export function ChatPanel({ slug }: { slug: string }) {
  return (
    <main className="panel">
      <h1>Chat</h1>
      <p>Tenant: {slug}</p>
    </main>
  );
}
