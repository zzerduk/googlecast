# Third-Party Notices

## ScreenCast

The WebRTC audio capture and peer implementation is adapted from
`ddagunts/ScreenCast`, revision `7e66bbe7ae5cc64c012bbe4987940be67925d137`.
The upstream project is licensed under Apache License 2.0. The license is
included in `app/src/main/assets/licenses/ScreenCast-LICENSE.txt`.

The adaptation removes video capture, manages MediaProjection independently,
uses the official Cast Sender SDK, makes capture failures terminal, and adds
bounded PCM buffering, serialized signaling, candidate queues, and lifecycle
cleanup.

The experimental WebRTC mode uses the upstream hosted receiver and App ID
`9098830C`. Product distribution requires confirmation of permission to use
that receiver or deployment and registration of a separately owned receiver.
