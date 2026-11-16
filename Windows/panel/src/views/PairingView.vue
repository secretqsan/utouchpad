<script setup>
import { computed, ref, watch } from "vue";
import QRCode from "qrcode";
import { writeText } from "@tauri-apps/plugin-clipboard-manager";
import AppIcon from "../components/AppIcon.vue";
import InlineNotice from "../components/InlineNotice.vue";
import NeoButton from "../components/NeoButton.vue";
import NeoCard from "../components/NeoCard.vue";
import StatusPill from "../components/StatusPill.vue";
import { useService } from "../composables/useService";

const { status, pairingEpoch, beginPairing, cancelPairing } = useService();

const defaultHint = "用手机 Utochpad App 扫描二维码。";

const pairing = ref(false);
const qrDataUrl = ref("");
const manualCode = ref("");
const pairVersionAtStart = ref(0);
const expiresAt = ref(0);
const hint = ref(defaultHint);
const busy = ref(false);
const notice = ref("");
const noticeKind = ref("info");

const pairState = computed(() => {
  if (pairing.value) return "unknown";
  return status.value?.paired ? "ok" : "off";
});

const connectionState = computed(() => {
  if (!status.value) return "unknown";
  return status.value.phoneConnected ? "ok" : "off";
});

const expiryLabel = computed(() =>
  expiresAt.value
    ? `有效期至 ${new Date(expiresAt.value).toLocaleTimeString("zh-CN", {
        hour12: false,
      })}`
    : "",
);

const pairButtonText = computed(() =>
  status.value?.paired ? "重新配对" : "开始配对",
);

function setNotice(message, kind = "info") {
  notice.value = message;
  noticeKind.value = kind;
}

function resetPairing() {
  pairing.value = false;
  qrDataUrl.value = "";
  manualCode.value = "";
  expiresAt.value = 0;
  pairVersionAtStart.value = 0;
  hint.value = defaultHint;
}

async function startPairing() {
  if (busy.value) return;
  busy.value = true;
  notice.value = "";

  try {
    const info = await beginPairing();
    qrDataUrl.value = await QRCode.toDataURL(info.qrJson, {
      errorCorrectionLevel: "Q",
      margin: 1,
      width: 300,
      color: { dark: "#2f2f2f", light: "#ffffff" },
    });
    manualCode.value = info.manualCode;
    pairVersionAtStart.value = info.pairVersion;
    expiresAt.value = new Date(info.expiresAt).getTime();
    pairing.value = true;
  } catch (error) {
    resetPairing();
    setNotice(error.message || "无法开始配对", "error");
  } finally {
    busy.value = false;
  }
}

async function cancel() {
  if (busy.value) return;
  busy.value = true;

  try {
    await cancelPairing();
  } catch (error) {
    setNotice(error.message || "取消配对失败", "error");
  } finally {
    busy.value = false;
    resetPairing();
  }
}

async function copyCode() {
  if (!manualCode.value) return;
  try {
    await writeText(manualCode.value);
    setNotice("已复制配对码。", "success");
  } catch {
    setNotice("复制失败，请手动选择文本复制。", "error");
  }
}

watch(status, (value) => {
  if (!pairing.value) return;

  if (!value) {
    resetPairing();
    return;
  }

  if (value.pairVersion !== pairVersionAtStart.value) {
    resetPairing();
    setNotice("配对成功。", "success");
  } else if (Date.now() > expiresAt.value) {
    resetPairing();
    hint.value = "二维码已过期，请重新配对。";
  }
});

watch(pairingEpoch, () => {
  resetPairing();
});
</script>

<template>
  <div class="mx-auto flex w-full max-w-[880px] flex-col gap-5">
    <header class="flex items-center justify-between gap-4">
      <h1 class="text-[26px] font-semibold leading-tight tracking-tight">配对手机</h1>
      <div class="flex items-center gap-2">
        <StatusPill
          :state="pairState"
          on="已配对"
          off="未配对"
          :unknown="pairing ? '等待扫码' : '检查中'"
        />
        <StatusPill
          :state="connectionState"
          on="手机已连接"
          off="手机未连接"
          unknown="检查中"
        />
      </div>
    </header>

    <InlineNotice v-if="notice" :message="notice" :kind="noticeKind" />

    <NeoCard>
      <div
        class="grid gap-7 md:grid-cols-[280px_minmax(0,1fr)] xl:grid-cols-[300px_minmax(0,1fr)]"
      >
        <div
          class="grid aspect-square w-full max-w-[300px] place-items-center rounded-3xl bg-white p-3.5 shadow-neo-inset"
        >
          <img
            v-if="qrDataUrl"
            :src="qrDataUrl"
            alt="配对二维码"
            class="h-full w-full object-contain [image-rendering:pixelated]"
          />
          <AppIcon v-else name="qr" :size="42" class="text-slate-400" />
        </div>

        <div class="flex min-w-0 flex-col gap-3.5">
          <p class="text-sm leading-6">{{ hint }}</p>

          <div class="flex flex-wrap items-center gap-2.5">
            <NeoButton
              v-if="!pairing"
              variant="primary"
              :disabled="busy || !status"
              @click="startPairing"
            >
              {{ busy ? "请稍候…" : pairButtonText }}
            </NeoButton>
            <NeoButton v-else :disabled="busy" @click="cancel">
              取消配对
            </NeoButton>
          </div>

          <p v-if="pairing" class="text-xs text-faint">{{ expiryLabel }}</p>

          <div v-if="pairing" class="flex flex-col gap-2.5">
            <p class="text-xs leading-5 text-muted">
              无法扫码？在手机上输入配对码。
            </p>
            <textarea
              class="w-full resize-y rounded-xl bg-panel px-3.5 py-3 font-mono text-[11.5px] leading-5 shadow-neo-inset outline-none focus:ring-2 focus:ring-accent/15"
              readonly
              rows="3"
              :value="manualCode"
            />
            <NeoButton size="sm" class="self-start" @click="copyCode">
              <AppIcon name="copy" :size="14" />
              <span>复制配对码</span>
            </NeoButton>
          </div>
        </div>
      </div>
    </NeoCard>
  </div>
</template>
