// Avatars for chat users: DiceBear notionists (artwork CC0), generated here in the browser from the
// user id as the seed, so no request leaves the page. Each id's SVG data URI is made once and kept.
// Pure: no random numbers, no clock, no stored state beyond the in-memory cache.
import { createAvatar } from '@dicebear/core';
import * as notionists from '@dicebear/notionists';
import { bareId } from './ids.ts';

/** One avatar's data URI from its seed. */
export const generateAvatar = (seed: string): string => createAvatar(notionists, { seed }).toDataUri();

export interface AvatarCache {
  /** The avatar of an id; the generator runs on the first lookup only. */
  get(id: string): string;
}

/** A cache over `generate`: each id is generated once and reused. */
export function createAvatarCache(generate: (seed: string) => string = generateAvatar): AvatarCache {
  const cache = new Map<string, string>();
  return {
    get(id) {
      let uri = cache.get(id);
      if (uri === undefined) {
        uri = generate(id);
        cache.set(id, uri);
      }
      return uri;
    },
  };
}

const shared = createAvatarCache();

/** The avatar data URI of a user id, from the page's shared cache; the same in blue and in green. */
export const avatarOf = (id: string): string => shared.get(bareId(id));

/** The smallest an avatar on the ring is drawn, in view units. */
export const MIN_AVATAR_RADIUS = 5;

/**
 * The radius of a user's avatar on the ring: its dot radius, at least MIN_AVATAR_RADIUS, and at most
 * half the room one of the `slots` places has along the ring.
 */
export function ringAvatarRadius(dotRadius: number, ringRadius: number, slots: number): number {
  const room = slots > 0 ? (Math.PI * ringRadius) / slots : Infinity;
  return Math.max(2, Math.min(Math.max(dotRadius, MIN_AVATAR_RADIUS), room));
}
