<script setup>
import { computed } from "vue";

const props = defineProps({
  message: { type: String, required: true },
  kind: { type: String, default: "info" },
});

const tone = computed(() => {
  if (props.kind === "success") {
    return "bg-success-soft/80 text-success";
  }
  if (props.kind === "error") {
    return "bg-danger-soft/80 text-danger";
  }
  return "bg-panel text-muted";
});
</script>

<template>
  <p class="rounded-2xl px-4 py-2.5 text-sm leading-6 shadow-neo-sm" :class="tone">
    {{ message }}
  </p>
</template>
