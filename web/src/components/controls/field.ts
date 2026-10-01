import { getContext } from "svelte";

/** Context key: the id a Field gives the control inside it, so its caption labels that control. */
export const FIELD_ID = Symbol("field-id");

export interface FieldSlot {
  id: string;
  taken: boolean;
}

/** The id of the Field this control sits in, for the first control that asks (ids must be unique). */
export function fieldId(): string | undefined {
  const slot = getContext<FieldSlot | undefined>(FIELD_ID);
  if (!slot || slot.taken) return undefined;
  slot.taken = true;
  return slot.id;
}

/** Clamps a typed number to the allowed range, rounding to a whole number unless decimals are allowed. */
export function clampNumber(raw: number, { min, max, integer }: { min?: number; max?: number; integer?: boolean }): number {
  let n = integer === false ? raw : Math.round(raw);
  if (min !== undefined) n = Math.max(min, n);
  if (max !== undefined) n = Math.min(max, n);
  return n;
}
