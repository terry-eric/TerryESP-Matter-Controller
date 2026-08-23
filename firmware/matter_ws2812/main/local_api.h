#pragma once

#include "app_priv.h"

// Starts the authenticated LAN API and UDP discovery service.
esp_err_t local_api_start(app_driver_handle_t strip);

// Returns this device's persistent, unique local-control key.
const char *local_api_key();
