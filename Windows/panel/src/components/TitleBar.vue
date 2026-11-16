<script setup>
import { onBeforeUnmount, onMounted, ref } from "vue";
import { isTauri } from "@tauri-apps/api/core";
import { getCurrentWindow } from "@tauri-apps/api/window";
import brandLogo from "../assets/utochpad-logo.svg";

const appWindow = isTauri() ? getCurrentWindow() : null;
const maximized = ref(false);
let unlistenResize = null;

async function syncMaximized() {
  if (!appWindow) return;
  maximized.value = await appWindow.isMaximized();
}

async function minimize() {
  await appWindow?.minimize();
}

async function toggleMaximize() {
  if (!appWindow) return;
  await appWindow.toggleMaximize();
  await syncMaximized();
}

async function close() {
  await appWindow?.close();
}

onMounted(async () => {
  if (!appWindow) return;
  await syncMaximized();
  unlistenResize = await appWindow.onResized(syncMaximized);
});

onBeforeUnmount(() => unlistenResize?.());
</script>

<template>
  <header
    data-tauri-drag-region="deep"
    class="flex h-11 shrink-0 select-none items-center pl-5"
  >
    <div class="flex items-center gap-2.5">
      <img :src="brandLogo" alt="" class="h-[22px] w-[22px] shrink-0" />
      <span class="text-[13px] font-semibold tracking-wide text-ink">Utochpad</span>
    </div>

    <div class="ml-auto flex h-full items-stretch">
      <button
        type="button"
        class="titlebar-action"
        title="最小化"
        aria-label="最小化"
        @click="minimize"
      >
        <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
          <path d="M3.5 8.5h9" />
        </svg>
      </button>

      <button
        type="button"
        class="titlebar-action"
        :title="maximized ? '还原' : '最大化'"
        :aria-label="maximized ? '还原' : '最大化'"
        @click="toggleMaximize"
      >
        <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
          <rect v-if="!maximized" x="3.5" y="3.5" width="9" height="9" rx="1" />
          <template v-else>
            <rect x="5.5" y="5.5" width="7" height="7" rx="1" />
            <path d="M4.5 10.5v-6h6" />
          </template>
        </svg>
      </button>

      <button
        type="button"
        class="titlebar-action titlebar-close"
        title="关闭"
        aria-label="关闭"
        @click="close"
      >
        <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
          <path d="M3.5 3.5l9 9M12.5 3.5l-9 9" />
        </svg>
      </button>
    </div>
  </header>
</template>

<style scoped>
.titlebar-action {
  display: grid;
  width: 46px;
  height: 100%;
  place-items: center;
  color: rgb(48 51 54 / 0.72);
  transition:
    background-color 120ms ease,
    color 120ms ease;
}

.titlebar-action:hover {
  background: rgb(0 0 0 / 0.055);
}

.titlebar-action:active {
  background: rgb(0 0 0 / 0.09);
}

.titlebar-close:hover,
.titlebar-close:active {
  background: #c42b1c;
  color: #fff;
}

.titlebar-action svg {
  fill: none;
  stroke: currentColor;
  stroke-width: 1.1;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
