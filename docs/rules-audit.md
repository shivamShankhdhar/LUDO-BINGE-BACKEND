# Initial rules audit

Source: sibling Android engine/LudoEngine.kt and engine/HexLudoEngine.kt on backend-integrated.

- Yard exit permits 1 or 6.
- Capture is required before home entry. Otherwise bypass step 100 returns to step 0 for another lap.
- Classic: 52-cell track, home entry 51, finish 56. Shared hex engine: 78-cell track, home entry 77, finish 83.
- Exact finish required; safe stars prevent captures. All enemy pawns sharing an unsafe destination are captured.
- Six, capture and home grant bonuses. Engine applies the third six before removing the bonus; ViewModel sequencing still needs auditing.
- Two-player classic seats are opposite. Hex physical seats are independent of selected color.
- HexLudoEngine color offsets differ from LudoEngine physical-seat offsets. Do not port the helper blindly.

Pending: ViewModel turn/termination audit, shared Java/Kotlin fixtures, five-player geometry. This is not completed rule parity.
