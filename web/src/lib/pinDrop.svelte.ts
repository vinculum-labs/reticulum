// Where letting go of the wire being dragged would make a pin (its dot on a frame's edge, canvas coordinates), so the
// dragged wire is drawn only that far. Set by the canvas while a wire is dragged past a frame's edge.

export const pinDrop = $state({ at: null as { x: number; y: number; output: boolean } | null });
