/* Simple Plugin API */
/* SPDX-FileCopyrightText: Copyright © 2020 Wim Taymans */
/* SPDX-License-Identifier: MIT */

#ifndef SPA_BLUETOOTH_AUDIO_H
#define SPA_BLUETOOTH_AUDIO_H

#ifdef __cplusplus
extern "C" {
#endif

/**
 * \addtogroup spa_param
 * \{
 */
enum spa_bluetooth_audio_codec {
    SPA_BLUETOOTH_AUDIO_CODEC_START,

    /* A2DP */
    SPA_BLUETOOTH_AUDIO_CODEC_SBC,
    SPA_BLUETOOTH_AUDIO_CODEC_SBC_XQ,
    SPA_BLUETOOTH_AUDIO_CODEC_MPEG,
    SPA_BLUETOOTH_AUDIO_CODEC_AAC,
    SPA_BLUETOOTH_AUDIO_CODEC_APTX,
    SPA_BLUETOOTH_AUDIO_CODEC_APTX_HD,
    SPA_BLUETOOTH_AUDIO_CODEC_LDAC,
    SPA_BLUETOOTH_AUDIO_CODEC_APTX_LL,
    SPA_BLUETOOTH_AUDIO_CODEC_APTX_LL_DUPLEX,
    SPA_BLUETOOTH_AUDIO_CODEC_FASTSTREAM,
    SPA_BLUETOOTH_AUDIO_CODEC_FASTSTREAM_DUPLEX,
    SPA_BLUETOOTH_AUDIO_CODEC_LC3PLUS_HR,
    SPA_BLUETOOTH_AUDIO_CODEC_OPUS_05,
    SPA_BLUETOOTH_AUDIO_CODEC_OPUS_05_51,
    SPA_BLUETOOTH_AUDIO_CODEC_OPUS_05_71,
    SPA_BLUETOOTH_AUDIO_CODEC_OPUS_05_DUPLEX,
    SPA_BLUETOOTH_AUDIO_CODEC_OPUS_05_PRO,

    /* HFP */
    SPA_BLUETOOTH_AUDIO_CODEC_CVSD = 0x100,
    SPA_BLUETOOTH_AUDIO_CODEC_MSBC,

    /* BAP */
    SPA_BLUETOOTH_AUDIO_CODEC_LC3 = 0x200,
};

/**
 * \}
 */

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif /* SPA_BLUETOOTH_AUDIO_H */
