import { ref } from "vue";
import { invoke } from "@tauri-apps/api/core";

const status = ref(null);
const serviceError = ref("");
const loading = ref(false);
const pairingEpoch = ref(0);

let pollTimer = null;
let inFlight = false;
let errorDismissed = false;

function errorText(error) {
  if (typeof error === "string") return error;
  if (error && typeof error.message === "string") return error.message;
  return "Utochpad 服务未运行或无法连接";
}

async function refreshStatus() {
  if (inFlight) return;
  inFlight = true;
  loading.value = true;

  try {
    status.value = await invoke("get_status");
    serviceError.value = "";
    errorDismissed = false;
  } catch (error) {
    status.value = null;
    if (!errorDismissed) serviceError.value = errorText(error);
  } finally {
    inFlight = false;
    loading.value = false;
  }
}

async function action(command, args) {
  try {
    return await invoke(command, args);
  } catch (error) {
    throw new Error(errorText(error));
  }
}

function startServicePolling() {
  if (pollTimer) return;
  refreshStatus();
  pollTimer = setInterval(refreshStatus, 2000);
}

function stopServicePolling() {
  if (!pollTimer) return;
  clearInterval(pollTimer);
  pollTimer = null;
}

function clearServiceError() {
  // Do not make the banner pop back two seconds later while the service is
  // still down; a successful poll resets the flag.
  errorDismissed = true;
  serviceError.value = "";
}

export function useService() {
  return {
    status,
    serviceError,
    loading,
    pairingEpoch,
    refresh: refreshStatus,
    clearError: clearServiceError,
    beginPairing: () => action("begin_pairing"),
    cancelPairing: async () => {
      const result = await action("cancel_pairing");
      await refreshStatus();
      return result;
    },
    unpair: async () => {
      const result = await action("unpair");
      pairingEpoch.value += 1;
      await refreshStatus();
      return result;
    },
    setPassword: async (password) => {
      const result = await action("set_password", { password });
      await refreshStatus();
      return result;
    },
    restartViiper: async () => {
      const result = await action("restart_viiper");
      await refreshStatus();
      return result;
    },
  };
}

export { startServicePolling, stopServicePolling };
