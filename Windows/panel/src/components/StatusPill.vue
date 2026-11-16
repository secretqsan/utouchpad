<script setup>
import { computed } from "vue";

const props = defineProps({
  state: { type: String, default: "unknown" },
  on: { type: String, default: "正常" },
  off: { type: String, default: "异常" },
  unknown: { type: String, default: "检查中" },
});

const label = computed(() => {
  if (props.state === "ok") return props.on;
  if (props.state === "off" || props.state === "critical") return props.off;
  return props.unknown;
});

const tone = computed(() => {
  if (props.state === "ok") return "bg-success-soft text-success";
  if (props.state === "critical") return "bg-danger-soft text-danger";
  if (props.state === "off") return "bg-slate-100 text-muted";
  return "bg-slate-100 text-faint";
});
</script>

<template>
  <span
    class="inline-flex items-center whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-semibold"
    :class="tone"
  >
    {{ label }}
  </span>
</template>
