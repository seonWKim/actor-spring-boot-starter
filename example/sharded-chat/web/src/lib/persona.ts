// A stable persona per user id: a display name from the built-in lists below and a locally
// generated avatar. It depends on the id alone, so the same id gives the same persona on every
// page load. Pure: nothing is fetched, no random numbers, no clock, no stored state.
import { avatarOf } from './avatars.ts';
import { bareId } from './ids.ts';

/** First names from varied countries. */
export const FIRST_NAMES = [
  'Aiko', 'Amara', 'Anders', 'Anika', 'Arjun', 'Beatriz', 'Binta', 'Carlos', 'Chen', 'Chidi',
  'Dalia', 'Dmitri', 'Elena', 'Emeka', 'Farid', 'Fatima', 'Giulia', 'Hana', 'Hiroshi', 'Ingrid',
  'Isabella', 'Jae-won', 'Kofi', 'Lars', 'Layla', 'Leila', 'Mateo', 'Mei', 'Nadia', 'Nikolai',
  'Olga', 'Omar', 'Priya', 'Rafael', 'Ravi', 'Sakura', 'Samira', 'Santiago', 'Sofia', 'Tariq',
  'Thandi', 'Valentina', 'Wei', 'Yara', 'Yusuf', 'Zainab', 'Zoltan', 'Min-jun', 'Aisha', 'Joao',
] as const;

/** Last names from varied countries. */
export const LAST_NAMES = [
  'Adeyemi', 'Andersson', 'Bianchi', 'Chaudhary', 'Costa', 'Dubois', 'Eriksen', 'Fernandez', 'Garcia', 'Hassan',
  'Ibrahim', 'Ivanov', 'Jensen', 'Kang', 'Khan', 'Kowalski', 'Kumar', 'Lee', 'Li', 'Martinez',
  'Mensah', 'Mori', 'Mueller', 'Nakamura', 'Nguyen', 'Novak', 'Okafor', 'Oliveira', 'Park', 'Patel',
  'Petrov', 'Rossi', 'Santos', 'Sato', 'Schmidt', 'Silva', 'Singh', 'Tanaka', 'Wang', 'Yilmaz',
] as const;

/** FNV-1a over the text's UTF-16 units, with a salt so the two names are picked independently. */
function hash(text: string, salt: number): number {
  let h = (0x811c9dc5 ^ salt) >>> 0;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  // A final mix, so ids that differ in the last character do not land on neighbouring names.
  h ^= h >>> 15;
  h = Math.imul(h, 0x2c1b3c6d) >>> 0;
  h ^= h >>> 12;
  return h >>> 0;
}

export interface Persona {
  id: string;
  /** First and last name. */
  name: string;
  /** The notionists avatar as an SVG data URI. */
  avatar: string;
}

/** The display name of a user id. */
// From the id without its color: chat-user-1 is the same person in blue and in green.
export const nameOf = (id: string): string =>
  `${FIRST_NAMES[hash(bareId(id), 1) % FIRST_NAMES.length]} ${LAST_NAMES[hash(bareId(id), 2) % LAST_NAMES.length]}`;

/** The persona of a user id: the same id always gives the same name and avatar. */
export const personaOf = (id: string): Persona => ({ id, name: nameOf(id), avatar: avatarOf(id) });
