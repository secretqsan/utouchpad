<script setup>
import { computed, ref } from "vue";

const props = defineProps({
  variant: { type: String, default: "default" },
  size: { type: String, default: "md" },
  disabled: Boolean,
});

const button = ref(null);

defineExpose({
  focus: () => button.value?.focus(),
});

const classes = computed(() => {
  const base =
    "inline-flex items-center justify-center gap-2 rounded-xl font-semibold transition duration-150 active:translate-y-px disabled:pointer-events-none disabled:opacity-50";

  const sizes =
    props.size === "sm" ? "px-3 py-1.5 text-xs" : "px-4 py-2.5 text-sm";

  const variants = {
    default:
      "bg-panel text-ink shadow-neo-sm hover:text-ink active:shadow-neo-inset",
    primary:
      "bg-panel text-accent shadow-neo-sm hover:text-accent active:shadow-neo-inset",
    danger:
      "bg-panel text-danger shadow-neo-sm hover:text-danger active:shadow-neo-inset",
  };

  return [base, sizes, variants[props.variant] || variants.default];
});
</script>

<template>
  <button ref="button" type="button" :disabled="disabled" :class="classes">
    <slot />
  </button>
</template>
