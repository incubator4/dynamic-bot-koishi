const AVATAR_KEYS = [
  "avatar",
  "avatarUrl",
  "avatar_url",
  "icon",
  "iconUrl",
  "icon_url",
  "photoUrl",
  "photo_url",
] as const;

/** First non-empty avatar URI among Satori/Koishi-shaped objects. */
export function pickAvatar(...sources: unknown[]): string {
  for (const source of sources) {
    const value = avatarFrom(source);
    if (value) return value;
  }
  return "";
}

function avatarFrom(source: unknown): string {
  if (typeof source === "string") return sanitize(source);
  if (!source || typeof source !== "object") return "";
  const rec = source as Record<string, unknown>;
  const nested = rec.user && typeof rec.user === "object"
    ? rec.user as Record<string, unknown>
    : undefined;
  for (const obj of [nested, rec]) {
    if (!obj) continue;
    for (const key of AVATAR_KEYS) {
      const value = sanitize(obj[key]);
      if (value) return value;
    }
  }
  return photoUri(rec.photo);
}

function photoUri(photo: unknown): string {
  if (typeof photo === "string") return sanitize(photo);
  if (!photo || typeof photo !== "object") return "";
  const rec = photo as Record<string, unknown>;
  for (const key of ["url", "big", "small", "src"] as const) {
    const value = sanitize(rec[key]);
    if (value) return value;
  }
  return "";
}

function sanitize(value: unknown): string {
  if (typeof value !== "string") return "";
  const trimmed = value.trim();
  if (!trimmed) return "";
  // Telegram file_id is a token without a URI scheme or path; skip it.
  if (!/^(https?:|data:|file:|\/\/)/i.test(trimmed) && !trimmed.includes("/")) {
    return "";
  }
  return trimmed;
}
