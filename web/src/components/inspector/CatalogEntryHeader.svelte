<script lang="ts">
  import { iconUrl, type CatalogEntry, type CatalogKind } from "../../lib/catalog";

  /** The top of a catalog browser's details column: the entry's icon, name and id. */
  let { entry, kind }: { entry: CatalogEntry; kind: CatalogKind } = $props();
</script>

<div class="flex items-center gap-2">
  <img src={iconUrl(kind, entry.id)} alt="" width="40" height="40" class="icon" />
  <div class="min-w-0">
    <div class="font-medium">{entry.name}</div>
    <div class="truncate font-mono text-xs muted" title={entry.id}>{entry.id}</div>
  </div>
</div>
