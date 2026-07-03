#include <obs-module.h>
#include <plugin-support.h>
#include "ucsp-source.h"

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE(PLUGIN_NAME, "en-US")

bool obs_module_load(void)
{
	obs_register_source(&ucsp_source_info);
	obs_log(LOG_INFO, "UCSP plugin loaded successfully (version %s)", PLUGIN_VERSION);
	return true;
}

void obs_module_unload(void)
{
	obs_log(LOG_INFO, "UCSP plugin unloaded");
}
