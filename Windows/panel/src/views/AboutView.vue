<script setup>
import { computed, onMounted, ref } from "vue";
import { getVersion } from "@tauri-apps/api/app";
import brandLogo from "../assets/utochpad-logo.svg";
import NeoCard from "../components/NeoCard.vue";
import { useService } from "../composables/useService";

const { status } = useService();

const panelVersion = ref("1.0.0");

onMounted(async () => {
  try {
    panelVersion.value = await getVersion();
  } catch {
    // Keep the fallback if the app metadata is unavailable.
  }
});

const rows = computed(() => [
  { label: "控制面板", value: `v${panelVersion.value}` },
  { label: "后台服务", value: status.value ? `v${status.value.version}` : "未运行" },
]);
</script>

<template>
  <div class="mx-auto flex w-full max-w-[880px] flex-col gap-5">
    <header>
      <h1 class="text-[26px] font-semibold leading-tight tracking-tight">关于</h1>
    </header>

    <NeoCard>
      <div class="flex items-center gap-4">
        <img :src="brandLogo" alt="" class="h-12 w-12 shrink-0" />
        <div class="min-w-0">
          <h2 class="text-base font-semibold">Utochpad</h2>
          <p class="mt-1 text-xs leading-5 text-muted">
            让 Android 手机充当 Windows 的指纹解锁、精密触控板与 Radial
            控制器。
          </p>
        </div>
      </div>

      <div class="mt-5 divide-y divide-black/[0.05]">
        <div
          v-for="row in rows"
          :key="row.label"
          class="flex items-center justify-between gap-3 py-2.5 first:pt-0 last:pb-0"
        >
          <span class="text-sm text-muted">{{ row.label }}</span>
          <span class="text-sm font-semibold tabular-nums">{{ row.value }}</span>
        </div>
      </div>
    </NeoCard>

    <p class="px-1 text-xs leading-5 text-faint">
      触控板和 Radial 通过 VIIPER 以虚拟 USB 设备实现。
    </p>
  </div>
</template>
