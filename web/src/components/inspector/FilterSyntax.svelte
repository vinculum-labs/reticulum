<script lang="ts">
  /** The filter language at a glance, shown beside every filter box and in the picker. */
</script>

<ul class="flex list-disc flex-col gap-0.5 pl-4 text-xs muted">
  <li><code>minecraft:*_ore</code>, <code>iron_ingot</code> — ids, with <code>*</code> as a wildcard (no namespace: any mod)</li>
  <li><code>#c:ores</code> or <code>tag:c:ores</code> — a tag; <code>mod:mekanism</code> — everything from a mod</li>
  <li><code>name:"iron"</code> — name contains; <code>name:/^Raw/</code> — a regular expression</li>
  <li><code>dura:&lt;25%</code> — durability; <code>ench:minecraft:fortune&gt;=3</code>, <code>ench:any</code> — enchantments</li>
  <li><code>is:fuel</code>, <code>food</code>, <code>stackable</code>, <code>damaged</code>, <code>enchanted</code>, <code>smeltable</code>; <code>has:minecraft:custom_name</code> — a component</li>
  <li><code>in:sink</code> — only what the destination already holds; <code>@name</code> — a saved filter</li>
  <li>Fluids: <code>temp:&gt;1000</code>, <code>is:gaseous</code>. Chemicals: <code>chem:radioactive</code>, <code>coolant</code>, <code>fuel</code></li>
  <li>Combine: terms side by side must all match (or <code>&amp;</code>), <code>|</code> means or, <code>!</code> means not, and brackets group: <code>(#c:ores | #c:raw_materials) !mod:create</code></li>
  <li>Empty matches anything</li>
</ul>

<style>
  code {
    font-family: ui-monospace, monospace;
    color: var(--ink);
  }
</style>
