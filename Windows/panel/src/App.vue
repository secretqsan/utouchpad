<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import TitleBar from "./components/TitleBar.vue";
import Sidebar from "./components/Sidebar.vue";
import MessageBanner from "./components/MessageBanner.vue";
import PairingView from "./views/PairingView.vue";
import PasswordView from "./views/PasswordView.vue";
import MaintenanceView from "./views/MaintenanceView.vue";
import AboutView from "./views/AboutView.vue";
import {
  startServicePolling,
  stopServicePolling,
  useService,
} from "./composables/useService";

const page = ref("pairing");
const { serviceError, clearError } = useService();
const views = {
  pairing: PairingView,
  password: PasswordView,
  maintenance: MaintenanceView,
  about: AboutView,
};

const currentView = computed(() => views[page.value]);

onMounted(startServicePolling);
onBeforeUnmount(stopServicePolling);
</script>

<template>
  <div class="flex h-screen flex-col bg-canvas">
    <TitleBar />

    <div class="grid min-h-0 flex-1 grid-cols-[224px_minmax(0,1fr)]">
      <Sidebar :current="page" @navigate="page = $event" />

      <main class="scrollbar-soft overflow-y-auto px-9 pt-4 pb-8">
        <MessageBanner
          v-if="serviceError"
          :message="serviceError"
          class="mx-auto max-w-[880px]"
          @close="clearError"
        />
        <KeepAlive>
          <component :is="currentView" />
        </KeepAlive>
      </main>
    </div>
  </div>
</template>
