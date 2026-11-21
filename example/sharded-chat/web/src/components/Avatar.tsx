import { avatarOf } from '../lib/avatars.ts';

/** A user's generated avatar as a round image of `size` px; the image is a data URI, so nothing is requested. */
export function Avatar({ userId, size = 24 }: { userId: string; size?: number }) {
  return (
    <img
      src={avatarOf(userId)}
      alt=""
      width={size}
      height={size}
      data-avatar={userId}
      className="shrink-0 rounded-full bg-log-hover"
      style={{ width: size, height: size }}
    />
  );
}
