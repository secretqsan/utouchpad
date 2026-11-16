<script setup>
import { computed, ref } from "vue";
import AppIcon from "../components/AppIcon.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import InlineNotice from "../components/InlineNotice.vue";
import NeoButton from "../components/NeoButton.vue";
import NeoCard from "../components/NeoCard.vue";
import StatusPill from "../components/StatusPill.vue";
import { useService } from "../composables/useService";

const { status, loading, refresh, restartViiper, unpair } = useService();

const busy = ref("");
const notice = ref("");
const noticeKind = ref("info");
const confirmOpen = ref(false);

const serviceState = computed(() => (status.value ? "ok" : "critical"));

const componentState = computed(() => {
  if (!status.value) return "unknown";
  return status.value.viiperRunning ? "ok" : "critical";
});

const info = computed(() => {
  if (!status.value) {
    return "服务未运行，请重新安装 Utochpad 或重启电脑。";
  }

  const s = status.value;
  const pending = s.pendingUnlockUser
    ? `\n等待手机验证：${s.pendingUnlockUser}`
    : "";

  return `端口     ${s.port}\n地址     ${(s.hosts || []).join(", ")}\n数据目录 ${s.dataDirectory}\n日志目录 ${s.logDirectory}${pending}`;
});

function setNotice(message, kind = "info") {
  notice.value = message;
  noticeKind.value = kind;
}

async function restart() {
  if (busy.value) return;
  busy.value = "restart";
  notice.value = "";

  try {
    await restartViiper();
    setNotice("输入组件已重启。", "success");
  } catch (error) {
    setNotice(error.message || "重启失败，请重试。", "error");
  } finally {
    busy.value = "";
  }
}

async function doUnpair() {
  confirmOpen.value = false;
  busy.value = "unpair";

  try {
    await unpair();
    setNotice("已解除配对。", "info");
  } catch (error) {
    setNotice(error.message || "解除配对失败，请重试。", "error");
  } finally {
    busy.value = "";
  }
}
</script>

<template>
  <div class="mx-auto flex w-full max-w-[880px] flex-col gap-5">
    <header class="flex items-center justify-between gap-4">
      <h1 class="text-[26px] font-semibold leading-tight tracking-tight">维护</h1>
      <div class="flex items-center gap-2">
        <StatusPill :state="serviceState" on="服务正常" off="服务已停止" />
        <StatusPill
          :state="componentState"
          on="组件运行中"
          off="组件未运行"
          unknown="检查中"
        />
      </div>
    </header>

    <InlineNotice v-if="notice" :message="notice" :kind="noticeKind" />

    <NeoCard>
      <h2 class="text-base font-semibold">诊断信息</h2>
      <pre
        class="mt-3 mb-4 whitespace-pre-wrap break-words rounded-2xl bg-panel p-4 font-mono text-xs leading-6 text-muted shadow-neo-inset"
        >{{ info }}</pre
      >

      <div class="flex flex-wrap items-center gap-2.5">
        <NeoButton :disabled="!!busy || loading" @click="refresh">
          <AppIcon name="refresh" :size="14" />
          <span>刷新</span>
        </NeoButton>
        <NeoButton :disabled="!!busy" @click="restart">
          <AppIcon name="restart" :size="14" />
          <span>重启输入组件</span>
        </NeoButton>
        <NeoButton
          variant="danger"
          :disabled="!!busy"
          @click="confirmOpen = true"
        >
          <AppIcon name="trash" :size="14" />
          <span>解除配对</span>
        </NeoButton>
      </div>
    </NeoCard>

    <ConfirmDialog
      :open="confirmOpen"
      title="解除配对"
      message="解除后手机会清除配对记录和保存的密码，需要重新配对才能连接。确定继续吗？"
      confirm-text="解除配对"
      cancel-text="取消"
      danger
      @confirm="doUnpair"
      @cancel="confirmOpen = false"
    />
  </div>
</template>
