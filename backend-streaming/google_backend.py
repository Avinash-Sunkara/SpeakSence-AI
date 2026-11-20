"""Google Cloud Speech-to-Text v2 StreamingRecognize backend (imports Google libraries lazily).

Credentials never leave this server. Use Application Default Credentials:
  * local dev:  gcloud auth application-default login
  * server:     GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json
"""
from __future__ import annotations

import os
from typing import Iterator

from stt_session import FatalBackendError, Result

PROJECT = os.environ.get("GOOGLE_CLOUD_PROJECT", "")
LOCATION = os.environ.get("STT_LOCATION", "us")  # chirp_3 te-IN: check the locations API for your region
MODEL = os.environ.get("STT_MODEL", "chirp_3")
USE_ALTERNATES = os.environ.get("STT_USE_ALTERNATE_LANGUAGES", "0") == "1"


class GoogleV2Backend:
    def __init__(self) -> None:
        if not PROJECT:
            raise RuntimeError("Set GOOGLE_CLOUD_PROJECT to your Google Cloud project id.")
        from google.api_core.client_options import ClientOptions
        from google.cloud.speech_v2 import SpeechClient

        self._client = SpeechClient(client_options=ClientOptions(api_endpoint=f"{LOCATION}-speech.googleapis.com"))

    def run(self, config: dict, audio: Iterator[bytes]) -> Iterator[Result]:
        from google.api_core import exceptions as gexc
        from google.cloud.speech_v2.types import cloud_speech

        languages = [config.get("language", "te-IN")]
        if USE_ALTERNATES:
            languages += [c for c in config.get("alternateLanguages", []) if c not in languages]

        recognition = cloud_speech.RecognitionConfig(
            explicit_decoding_config=cloud_speech.ExplicitDecodingConfig(
                encoding=cloud_speech.ExplicitDecodingConfig.AudioEncoding.LINEAR16,
                sample_rate_hertz=int(config.get("sampleRate", 16000)),
                audio_channel_count=1,
            ),
            language_codes=languages,
            model=MODEL,
            features=cloud_speech.RecognitionFeatures(enable_automatic_punctuation=True),
        )
        streaming = cloud_speech.StreamingRecognitionConfig(
            config=recognition,
            streaming_features=cloud_speech.StreamingRecognitionFeatures(
                interim_results=bool(config.get("interimResults", True))
            ),
        )
        recognizer = f"projects/{PROJECT}/locations/{LOCATION}/recognizers/_"

        def requests():
            yield cloud_speech.StreamingRecognizeRequest(recognizer=recognizer, streaming_config=streaming)
            for chunk in audio:
                yield cloud_speech.StreamingRecognizeRequest(audio=chunk)

        try:
            for response in self._client.streaming_recognize(requests=requests()):
                interim = []
                for result in response.results:
                    if not result.alternatives:
                        continue
                    text = result.alternatives[0].transcript.strip()
                    if not text:
                        continue
                    if result.is_final:
                        yield Result(True, text)
                    else:
                        interim.append(text)
                if interim:
                    yield Result(False, " ".join(interim))
        except (gexc.PermissionDenied, gexc.Unauthenticated, gexc.InvalidArgument, gexc.NotFound) as e:
            raise FatalBackendError(f"Google Speech-to-Text rejected the request: {e.__class__.__name__}: {e}") from e
