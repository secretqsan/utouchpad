<script setup>
import { computed, ref } from "vue";
import InlineNotice from "../components/InlineNotice.vue";
import NeoButton from "../components/NeoButton.vue";
import NeoCard from "../components/NeoCard.vue";
import StatusPill from "../components/StatusPill.vue";
import { useService } from "../composables/useService";

const { status, setPassword } = useService();

const password = ref("");
const sending = ref(false);
const message = ref("");
const messageKind = ref("info");

const phoneConnected = computed(() => status.value?.phoneConnected === true);
const canSend = computed(
  () => phoneConnected.value && password.value.length > 0 && !sending.value,
);

const passwordState = computed(() => {
  if (!status.value || !phoneConnected.value) return "unknown";
  return status.value.phoneHasPassword ? "ok" : "off";
});

async function send() {
  if (!canSend.value) return;

  sending.value = true;
  message.value = "请在手机上确认指纹…";
  messageKind.value = "info";

  try {
    await setPassword(password.value);
    password.value = "";
    message.value = "已发送到手机";
    messageKind.value = "success";
  } catch (error) {
    message.value = error.message || "发送失败，请重试。";
    messageKind.value = "error";
  } finally {
    sending.value = false;
  }
}
</script>

<template>
  <div class="mx-auto flex w-full max-w-[880px] flex-col gap-5">
    <header class="flex items-center justify-between gap-4">
      <h1 class="text-[26px] font-semibold leading-tight tracking-tight">
        Windows 密码
      </h1>
      <StatusPill :state="passwordState" on="已保存" off="未保存" unknown="—" />
    </header>

    <NeoCard>
      <div
        class="flex items-center gap-3 max-[900px]:flex-col max-[900px]:items-stretch"
      >
        <input
          v-model="password"
          class="min-w-0 flex-1 rounded-xl bg-panel px-3.5 py-3 text-sm shadow-neo-inset outline-none transition focus:ring-2 focus:ring-accent/15"
          type="password"
          autocomplete="off"
          spellcheck="false"
          placeholder="Windows 账户密码"
          :disabled="sending"
          @keyup.enter="send"
        />
        <NeoButton variant="primary" :disabled="!canSend" @click="send">
          {{ sending ? "等待手机确认…" : "发送到手机" }}
        </NeoButton>
      </div>

      <p class="mt-2 text-xs leading-5 text-muted">
        {{
          phoneConnected
            ? "请填写 Windows 账户密码，不是 PIN 码。"
            : "手机未连接，请打开手机上的 Utochpad。"
        }}
      </p>

      <InlineNotice v-if="message" :message="message" :kind="messageKind" class="mt-3" />
    </NeoCard>
  </div>
</template>
