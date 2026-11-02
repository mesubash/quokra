import { useState } from "react";
import { ApiError, type Conversation, type Rating } from "../lib/api";

interface Props {
  conversation: Conversation;
  rate: (positive: boolean, comment?: string) => Promise<unknown>;
}

// Under a closed chat with an agent: thumbs up or down, then "Anything to add?", then thanks.
// Live updates don't carry the rating, so the one given here is kept in this card.
export function RatingCard({ conversation, rate }: Props) {
  const [given, setGiven] = useState<Rating | null>(null);
  const [commenting, setCommenting] = useState(false);
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const rating = given ?? conversation.rating ?? null;
  if (conversation.status !== "RESOLVED" || (!rating && !conversation.canRate)) return null;

  async function send(positive: boolean, text?: string) {
    setBusy(true);
    setError(null);
    try {
      await rate(positive, text);
      setGiven({ positive, comment: text ?? null });
      setCommenting(!text);
    } catch (e) {
      // Rated already (in another tab, say): nothing more to ask.
      if (e instanceof ApiError && e.code === "ALREADY_RATED") {
        setGiven(rating ?? { positive, comment: null });
        setCommenting(false);
      } else {
        setError("Couldn't send that. Please try again.");
      }
    } finally {
      setBusy(false);
    }
  }

  const who = conversation.assignee?.name ?? "our team";
  return (
    <section className="rating-card" aria-label="Rate this chat">
      {!rating ? (
        <>
          <p>How was your chat with {who}?</p>
          <div className="rating-thumbs">
            <button type="button" aria-label="Good" title="Good" disabled={busy} onClick={() => void send(true)}>
              👍
            </button>
            <button type="button" aria-label="Not good" title="Not good" disabled={busy} onClick={() => void send(false)}>
              👎
            </button>
          </div>
        </>
      ) : commenting ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (comment.trim()) void send(rating.positive, comment.trim());
          }}
        >
          <label htmlFor="rating-comment">Thanks! Anything to add?</label>
          <textarea
            id="rating-comment"
            rows={2}
            maxLength={1000}
            value={comment}
            onChange={(e) => setComment(e.target.value)}
          />
          <div className="rating-actions">
            <button type="button" className="link-button" onClick={() => setCommenting(false)}>
              No thanks
            </button>
            <button type="submit" className="button" disabled={busy || !comment.trim()}>
              Send
            </button>
          </div>
        </form>
      ) : (
        <p role="status">
          <span aria-hidden="true">{rating.positive ? "👍" : "👎"}</span> Thanks for your feedback.
        </p>
      )}
      {error && (
        <p className="rating-error" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
