<script setup>
import AppIcon from "./AppIcon.vue";

defineProps({
  message: { type: String, required: true },
});

defineEmits(["close"]);
</script>

<template>
  <div
    class="mb-5 flex w-full items-start gap-2.5 rounded-2xl bg-danger-soft/80 px-4 py-3 text-sm text-danger shadow-neo-sm"
    role="status"
  >
    <AppIcon name="warning" :size="18" class="mt-0.5 shrink-0" />
    <div class="min-w-0 flex-1">{{ message }}</div>
    <button
      type="button"
      class="-mr-1 rounded-lg p-1 opacity-70 transition hover:opacity-100"
      aria-label="关闭"
      @click="$emit('close')"
    >
      <AppIcon name="close" :size="14" />
    </button>
  </div>
</template>
