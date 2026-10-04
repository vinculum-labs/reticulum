/**
 * Whether a rebuilt card or wire is the same as the one on the canvas: then the old object is kept, so Svelte Flow
 * (which compares by identity) skips it instead of re-rendering and re-measuring the whole sheet on every metrics update.
 */
import type { Edge, Node } from "@xyflow/svelte";
import type { NodeData } from "./graph";

function sameValue(a: unknown, b: unknown): boolean {
  return a === b || (typeof a === "object" && typeof b === "object" && JSON.stringify(a) === JSON.stringify(b));
}

function sameData(a: object | undefined, b: object | undefined): boolean {
  const x = (a ?? {}) as Record<string, unknown>;
  const y = (b ?? {}) as Record<string, unknown>;
  for (const key of new Set([...Object.keys(x), ...Object.keys(y)])) if (!sameValue(x[key], y[key])) return false;
  return true;
}

export function sameNode(a: Node<NodeData>, b: Node<NodeData>): boolean {
  if (a.type !== b.type || a.parentId !== b.parentId || !!a.selected !== !!b.selected || a.position.x !== b.position.x || a.position.y !== b.position.y) return false;
  if (a.width !== b.width || a.height !== b.height || a.expandParent !== b.expandParent) return false;
  return sameData(a.data, b.data);
}

export function sameEdge(a: Edge, b: Edge): boolean {
  return (
    a.source === b.source &&
    a.target === b.target &&
    a.sourceHandle === b.sourceHandle &&
    a.targetHandle === b.targetHandle &&
    a.type === b.type &&
    !!a.selected === !!b.selected &&
    a.animated === b.animated &&
    a.label === b.label &&
    a.style === b.style &&
    a.class === b.class &&
    sameData(a.data, b.data)
  );
}

export function sameIds(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((id, i) => id === b[i]);
}
