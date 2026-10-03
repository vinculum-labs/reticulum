<script lang="ts">
  /**
   * A pin as the canvas draws it, for headings and buttons: a filled dot where things come out (outputs; a sheet's
   * input pins, which feed what is inside), a ring where they go in. In {@code color}, or the text's.
   */
  let { solid = false, color = "currentColor", size = 10 }: { solid?: boolean; color?: string; size?: number } = $props();
</script>

<span
  class="pin-mark"
  aria-hidden="true"
  style="width: {size}px; height: {size}px; border-color: {color}; background: {solid ? color : 'transparent'}"
></span>

<style>
  .pin-mark {
    display: inline-block;
    flex-shrink: 0;
    border: 2px solid;
    border-radius: 50%;
  }
</style>
