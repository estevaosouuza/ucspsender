#include "ucsp-source.h"
#include <plugin-support.h>

#define UCSP_DEFAULT_PORT 5600

struct ucsp_source_data {
	obs_source_t *source;
	uint16_t listen_port;
};

static const char *ucsp_source_get_name(void *)
{
	return obs_module_text("UcspSourceName");
}

static void ucsp_source_update(void *data, obs_data_t *settings)
{
	auto *ctx = static_cast<ucsp_source_data *>(data);
	ctx->listen_port = static_cast<uint16_t>(obs_data_get_int(settings, "listen_port"));
}

static void *ucsp_source_create(obs_data_t *settings, obs_source_t *source)
{
	auto *ctx = new ucsp_source_data{};
	ctx->source = source;
	ucsp_source_update(ctx, settings);
	obs_log(LOG_INFO, "ucsp_source created, listen_port=%d", ctx->listen_port);
	return ctx;
}

static void ucsp_source_destroy(void *data)
{
	auto *ctx = static_cast<ucsp_source_data *>(data);
	obs_log(LOG_INFO, "ucsp_source destroyed");
	delete ctx;
}

static void ucsp_source_defaults(obs_data_t *settings)
{
	obs_data_set_default_int(settings, "listen_port", UCSP_DEFAULT_PORT);
}

static obs_properties_t *ucsp_source_properties(void *)
{
	obs_properties_t *props = obs_properties_create();
	obs_properties_add_int(props, "listen_port", obs_module_text("ListenPort"), 1024, 65535, 1);
	return props;
}

struct obs_source_info ucsp_source_info = {
	.id = "ucsp_source",
	.type = OBS_SOURCE_TYPE_INPUT,
	.output_flags = OBS_SOURCE_ASYNC_VIDEO,
	.get_name = ucsp_source_get_name,
	.create = ucsp_source_create,
	.destroy = ucsp_source_destroy,
	.get_defaults = ucsp_source_defaults,
	.get_properties = ucsp_source_properties,
	.update = ucsp_source_update,
};
