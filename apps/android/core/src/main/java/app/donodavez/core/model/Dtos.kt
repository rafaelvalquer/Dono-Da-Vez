package app.donodavez.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// Contrato: packages/contracts/openapi.yaml. Dinheiro é sempre String decimal (ver Money.kt).

@Serializable
data class WalletDto(
    val balance: String = "0.00",
    val reserved: String = "0.00",
    val available: String = "0.00",
    val frozen: Boolean = false,
    @SerialName("collectable_income") val collectableIncome: String? = null,
)

@Serializable
data class NotificationPrefs(val proposals: Boolean = true, val transfers: Boolean = true, val reminders: Boolean = true)

@Serializable
data class InactivityDto(
    @SerialName("last_qualified_activity_at") val lastQualifiedActivityAt: String? = null,
    @SerialName("inactive_after") val inactiveAfter: String? = null,
)

@Serializable
data class ProfileDto(
    val id: String,
    val nickname: String? = null,
    @SerialName("avatar_id") val avatarId: String = "avatar_01",
    val status: String = "active",
    @SerialName("email_confirmed") val emailConfirmed: Boolean = false,
    @SerialName("initial_grant_received") val initialGrantReceived: Boolean = false,
    @SerialName("notification_prefs") val notificationPrefs: NotificationPrefs = NotificationPrefs(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("deletion_requested_at") val deletionRequestedAt: String? = null,
    val inactivity: InactivityDto? = null,
)

@Serializable
data class CategoryDto(
    val code: String,
    val name: String,
    @SerialName("base_price") val basePrice: String,
    @SerialName("daily_income") val dailyIncome: String,
    @SerialName("visual_key") val visualKey: String,
)

@Serializable
data class GameConfigDto(
    val version: Int = 1,
    @SerialName("initial_balance") val initialBalance: String = "5000.00",
    @SerialName("daily_income_rate") val dailyIncomeRate: String = "0.01",
    @SerialName("interaction_radius_m") val interactionRadiusM: Int = 80,
    @SerialName("max_gps_accuracy_m") val maxGpsAccuracyM: Int = 30,
    @SerialName("max_location_age_s") val maxLocationAgeS: Int = 15,
    @SerialName("presence_proof_ttl_s") val presenceProofTtlS: Int = 30,
    @SerialName("max_purchase_speed_kmh") val maxPurchaseSpeedKmh: Int = 15,
    @SerialName("proposal_response_window_s") val proposalResponseWindowS: Int = 86_400,
    @SerialName("owner_inactivity_s") val ownerInactivityS: Int = 1_209_600,
    @SerialName("forced_purchase_window_s") val forcedPurchaseWindowS: Int = 86_400,
    @SerialName("transfer_protection_s") val transferProtectionS: Int = 86_400,
    @SerialName("forced_premium") val forcedPremium: String = "1.20",
    @SerialName("max_open_proposals_per_buyer") val maxOpenProposalsPerBuyer: Int = 10,
    @SerialName("allow_mock_location") val allowMockLocation: Boolean = false,
    val categories: List<CategoryDto> = emptyList(),
)

@Serializable
data class LatLonDto(val lat: Double, val lon: Double)

@Serializable
data class RegionDto(
    val id: String,
    val code: String,
    val name: String,
    val attribution: String? = null,
    @SerialName("dataset_version") val datasetVersion: String? = null,
    val bbox: List<Double> = emptyList(),
    val center: LatLonDto? = null,
)

@Serializable
data class BootstrapDto(
    @SerialName("server_time") val serverTime: String? = null,
    @SerialName("min_app_version") val minAppVersion: Int = 0,
    @SerialName("update_required") val updateRequired: Boolean = false,
    val config: GameConfigDto = GameConfigDto(),
    val profile: ProfileDto,
    val wallet: WalletDto? = null,
    val regions: List<RegionDto> = emptyList(),
)

@Serializable
data class MapResponseDto(
    val mode: String = "none",
    @SerialName("region_status") val regionStatus: String = "unavailable",
    val regions: List<RegionDto> = emptyList(),
    val features: JsonArray = JsonArray(emptyList()),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("server_time") val serverTime: String? = null,
)

@Serializable
data class PropertySummaryDto(
    val id: String,
    val category: String? = null,
    val subtype: String? = null,
    @SerialName("address_label") val addressLabel: String? = null,
    @SerialName("address_status") val addressStatus: String = "missing",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val eligibility: String? = null,
    val version: Long? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
    val state: String? = null,
)

@Serializable
data class RegionInfoDto(
    val code: String,
    val name: String,
    @SerialName("release_status") val releaseStatus: String = "released",
    val attribution: String? = null,
)

@Serializable
data class PropertyInfoDto(
    val id: String,
    val category: String? = null,
    val subtype: String? = null,
    @SerialName("address_label") val addressLabel: String? = null,
    @SerialName("address_status") val addressStatus: String = "missing",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val version: Long = 1,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("classification_status") val classificationStatus: String = "resolved",
    @SerialName("classification_confidence") val classificationConfidence: Double? = null,
    val eligibility: String = "eligible",
    @SerialName("eligibility_reason") val eligibilityReason: String? = null,
    @SerialName("interaction_point") val interactionPoint: LatLonDto? = null,
    @SerialName("interaction_quality") val interactionQuality: String? = null,
    @SerialName("height_m") val heightM: Double? = null,
    @SerialName("height_source") val heightSource: String? = null,
    @SerialName("visual_model") val visualModel: String? = null,
    @SerialName("units_count") val unitsCount: Int? = null,
    val region: RegionInfoDto? = null,
)

@Serializable
data class OwnershipDto(
    @SerialName("ownership_id") val ownershipId: String,
    @SerialName("owner_nickname") val ownerNickname: String? = null,
    @SerialName("owner_avatar_id") val ownerAvatarId: String? = null,
    @SerialName("is_mine") val isMine: Boolean = false,
    @SerialName("purchase_price") val purchasePrice: String,
    @SerialName("acquired_at") val acquiredAt: String? = null,
    @SerialName("owner_daily_income") val ownerDailyIncome: String? = null,
    @SerialName("protected_until") val protectedUntil: String? = null,
)

/**
 * Bônus de inauguração: renda extra por dia nos primeiros dias de uma compra do sistema. Em uma titularidade
 * vem com `until`; na oferta de compra (`actions.purchase`) vem com a duração em `seconds`.
 */
@Serializable
data class OpeningBonusDto(
    val daily: String,
    val until: String? = null,
    val seconds: Long? = null,
)

@Serializable
data class MineDto(
    val accrued: String = "0.00",
    val collectable: String = "0.00",
    @SerialName("opening_bonus") val openingBonus: OpeningBonusDto? = null,
    @SerialName("pending_offers") val pendingOffers: Int = 0,
)

@Serializable
data class ActionInfoDto(
    val allowed: Boolean = false,
    val price: String? = null,
    @SerialName("daily_income") val dailyIncome: String? = null,
    @SerialName("min_amount") val minAmount: String? = null,
    @SerialName("proposal_id") val proposalId: String? = null,
    @SerialName("eligible_until") val eligibleUntil: String? = null,
    @SerialName("requires_presence") val requiresPresence: Boolean = true,
    val reason: String? = null,
    @SerialName("opening_bonus") val openingBonus: OpeningBonusDto? = null,
)

@Serializable
data class ActionsDto(
    val purchase: ActionInfoDto = ActionInfoDto(),
    val propose: ActionInfoDto = ActionInfoDto(),
    @SerialName("forced_inactivity") val forcedInactivity: ActionInfoDto = ActionInfoDto(),
    @SerialName("forced_silence") val forcedSilence: ActionInfoDto = ActionInfoDto(),
)

@Serializable
data class TradeDto(
    @SerialName("trade_id") val tradeId: String,
    val type: String,
    val price: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("buyer_nickname") val buyerNickname: String? = null,
    @SerialName("seller_nickname") val sellerNickname: String? = null,
)

@Serializable
data class PropertyDetailDto(
    @SerialName("server_time") val serverTime: String? = null,
    val property: PropertyInfoDto,
    val ownership: OwnershipDto? = null,
    @SerialName("reference_price") val referencePrice: String = "0.00",
    @SerialName("daily_income_rate") val dailyIncomeRate: String = "0.01",
    val mine: MineDto? = null,
    @SerialName("my_proposal") val myProposal: ProposalDto? = null,
    val actions: ActionsDto = ActionsDto(),
    val history: List<TradeDto> = emptyList(),
)

@Serializable
data class ProposalDto(
    val id: String,
    val property: PropertySummaryDto? = null,
    @SerialName("ownership_id") val ownershipId: String? = null,
    val direction: String = "sent",
    @SerialName("counterpart_nickname") val counterpartNickname: String? = null,
    val amount: String,
    val status: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("eligible_until") val eligibleUntil: String? = null,
    @SerialName("forced_price") val forcedPrice: String? = null,
    val version: Int = 1,
    @SerialName("rules_version") val rulesVersion: Int? = null,
    @SerialName("trade_id") val tradeId: String? = null,
)

@Serializable
data class ProposalPageDto(
    val items: List<ProposalDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class OperationResultDto(
    val ok: Boolean = true,
    @SerialName("operation_id") val operationId: String? = null,
    @SerialName("trade_id") val tradeId: String? = null,
    @SerialName("ownership_id") val ownershipId: String? = null,
    @SerialName("property_id") val propertyId: String? = null,
    @SerialName("purchase_price") val purchasePrice: String? = null,
    @SerialName("daily_income") val dailyIncome: String? = null,
    @SerialName("opening_bonus") val openingBonus: OpeningBonusDto? = null,
    @SerialName("acquired_at") val acquiredAt: String? = null,
    val proposal: ProposalDto? = null,
    val wallet: WalletDto? = null,
    val collected: String? = null,
    val status: String? = null,
)

@Serializable
data class PortfolioItemDto(
    @SerialName("ownership_id") val ownershipId: String,
    @SerialName("acquired_at") val acquiredAt: String? = null,
    @SerialName("acquired_via") val acquiredVia: String? = null,
    @SerialName("purchase_price") val purchasePrice: String,
    @SerialName("daily_income") val dailyIncome: String,
    val accrued: String = "0.00",
    val collectable: String = "0.00",
    @SerialName("income_credited") val incomeCredited: String = "0.00",
    @SerialName("opening_bonus") val openingBonus: OpeningBonusDto? = null,
    @SerialName("pending_offers") val pendingOffers: Int = 0,
    val property: PropertySummaryDto,
)

@Serializable
data class PortfolioTotalsDto(
    val count: Int = 0,
    @SerialName("daily_income") val dailyIncome: String = "0.00",
    @SerialName("opening_bonus_daily") val openingBonusDaily: String = "0.00",
    val collectable: String = "0.00",
    val invested: String = "0.00",
)

@Serializable
data class PortfolioDto(
    val items: List<PortfolioItemDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    val totals: PortfolioTotalsDto = PortfolioTotalsDto(),
    @SerialName("server_time") val serverTime: String? = null,
)

@Serializable
data class HoldDto(
    @SerialName("hold_id") val holdId: String,
    @SerialName("proposal_id") val proposalId: String? = null,
    val amount: String,
    val status: String,
    @SerialName("release_reason") val releaseReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("released_at") val releasedAt: String? = null,
)

@Serializable
data class LedgerEntryDto(
    @SerialName("entry_id") val entryId: Long,
    val amount: String,
    @SerialName("entry_type") val entryType: String,
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("transaction_kind") val transactionKind: String,
    @SerialName("created_at") val createdAt: String? = null,
    val property: PropertySummaryDto? = null,
    @SerialName("proposal_id") val proposalId: String? = null,
)

@Serializable
data class WalletPageDto(
    val wallet: WalletDto? = null,
    val entries: List<LedgerEntryDto> = emptyList(),
    val holds: List<HoldDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class HistoryItemDto(
    val kind: String,
    val at: String,
    val ref: String,
    val amount: String,
    val property: PropertySummaryDto? = null,
    val detail: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class HistoryPageDto(
    val items: List<HistoryItemDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class NotificationDto(
    val id: String,
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
)

@Serializable
data class NotificationPageDto(
    val items: List<NotificationDto> = emptyList(),
    val unread: Int = 0,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class ReportResultDto(
    @SerialName("report_id") val reportId: Long? = null,
    val protocol: String? = null,
    val status: String? = null,
)
