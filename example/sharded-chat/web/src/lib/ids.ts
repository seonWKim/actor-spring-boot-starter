// Both colors run the same chat, so the control app gives every id its color: chat-user-1@green.
// Names, avatars and shards depend on the id the chat nodes know, without the color. Pure.

/** The id as the chat nodes know it: "chat-user-1@green" → "chat-user-1". */
export const bareId = (id: string): string => {
  const at = id.indexOf('@');
  return at < 0 ? id : id.slice(0, at);
};

/** The color an id names, or null. */
export const colorOfId = (id: string): string | null => {
  const at = id.indexOf('@');
  return at < 0 ? null : id.slice(at + 1);
};
