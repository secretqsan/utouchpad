<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import NeoButton from "./NeoButton.vue";

const props = defineProps({
  open: Boolean,
  title: { type: String, default: "" },
  message: { type: String, default: "" },
  confirmText: { type: String, default: "确定" },
  cancelText: { type: String, default: "取消" },
  danger: Boolean,
});

const emit = defineEmits(["confirm", "cancel"]);
const confirmButton = ref(null);

function onKeydown(event) {
  if (event.key === "Escape" && props.open) emit("cancel");
}

watch(
  () => props.open,
  async (open) => {
    if (!open) return;
    await nextTick();
    confirmButton.value?.focus();
  },
);

onMounted(() => window.addEventListener("keydown", onKeydown));
onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown));
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div
        v-if="open"
        class="fixed inset-0 z-50 grid place-items-center bg-black/20 p-6 backdrop-blur-sm"
        @click.self="emit('cancel')"
      >
        <div
          class="w-full max-w-md rounded-3xl bg-panel p-6 shadow-neo-lg"
          role="dialog"
          aria-modal="true"
          :aria-label="title"
        >
          <h2 class="text-lg font-semibold">{{ title }}</h2>
          <p class="mt-2.5 text-sm leading-6 text-muted">{{ message }}</p>
          <div class="mt-7 flex justify-end gap-2.5">
            <NeoButton @click="emit('cancel')">{{ cancelText }}</NeoButton>
            <NeoButton
              ref="confirmButton"
              :variant="danger ? 'danger' : 'primary'"
              @click="emit('confirm')"
            >
              {{ confirmText }}
            </NeoButton>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
