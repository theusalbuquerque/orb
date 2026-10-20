@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.music.orb.ui.screens

import com.music.orb.ui.components.orbSheetProgressiveGridItem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.blur

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import com.music.orb.ui.components.OrbConfirmationDialog as AlertDialog
import com.music.orb.ui.components.OrbModalBottomSheet as ModalBottomSheet
import com.music.orb.ui.components.OrbGlassButton as Button
import com.music.orb.ui.components.OrbGlassOutlinedButton as OutlinedButton
import com.music.orb.ui.components.OrbGlassTextButton as TextButton
import com.music.orb.ui.components.OrbGlassIconButton as IconButton
import com.music.orb.ui.components.OrbGlassFilledIconButton as FilledIconButton
import com.music.orb.ui.components.OrbGlassInputChip as InputChip
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import com.music.orb.ui.components.orbOverlayGlassBaseColor
import com.music.orb.ui.components.orbGlassSurface
import com.music.orb.R
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.YtMusicRepository
import com.music.orb.data.model.SearchFilter
import com.music.orb.data.model.SearchResult
import com.music.orb.data.model.Song
import com.music.orb.data.rooms.ListeningRoom
import com.music.orb.data.rooms.RoomException
import com.music.orb.data.rooms.RoomRepository
import com.music.orb.playback.rememberPlayerState
import com.music.orb.ui.components.PAGE_GUTTER
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt


/** Unframed action: no glass pill, surface, stroke or oversized internal padding. */
@Composable
private fun RoomPlainTextAction(
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
    )
}

@Composable
internal fun RoomsLibrarySection(onCreate: () -> Unit, onJoin: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember(context) { RoomRepository.init(context); true }
    val rooms by RoomRepository.rooms.collectAsStateWithLifecycle()
    val current by RoomRepository.room.collectAsStateWithLifecycle()
    val user = com.music.orb.data.social.SocialRepository.currentUserId()
    LaunchedEffect(user, current?.id, current?.coverPath) {
        runCatching { RoomRepository.loadRooms() }
    }
    Shelf(
        shelf = com.music.orb.data.model.HomeShelf(
            title = stringResource(R.string.rooms_title),
            items = listOf(
                com.music.orb.data.model.ShelfItem(title = stringResource(R.string.rooms_create), subtitle = "", thumbnailUrl = null, videoId = null, browseId = "room:create"),
                com.music.orb.data.model.ShelfItem(title = stringResource(R.string.rooms_join), subtitle = "", thumbnailUrl = null, videoId = null, browseId = "room:join"),
            ) + rooms.map { room -> com.music.orb.data.model.ShelfItem(
                title = room.title,
                subtitle = stringResource(R.string.rooms_participant_count, room.count, room.maxGuests + 1),
                thumbnailUrl = room.coverUrl, videoId = null, browseId = "room:" + room.id,
            ) },
        ),
        onItemClick = { item ->
            when (item.browseId) {
                "room:create" -> onCreate()
                "room:join" -> onJoin()
                else -> scope.launch {
                    try {
                        RoomRepository.open(requireNotNull(item.browseId).removePrefix("room:"))
                        onOpen()
                    }
                    catch (exception: Exception) {
                        RoomRepository.issue.value = (exception as? RoomException)?.reason ?: "ROOM_NETWORK"
                        android.widget.Toast.makeText(context, R.string.rooms_error_network, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        },
        expressive = com.music.orb.ui.flavor.OrbFlavorUi.expressive,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomFlowScreen(initialAction: String, controller: MediaController?, transferActive: Boolean = false, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember(context) { RoomRepository.init(context); true }
    val room by RoomRepository.room.collectAsStateWithLifecycle()
    val issue by RoomRepository.issue.collectAsStateWithLifecycle()
    val player = rememberPlayerState(controller)
    var stage by rememberSaveable { mutableStateOf(initialAction) }
    var title by rememberSaveable { mutableStateOf("") }
    var guests by rememberSaveable { mutableFloatStateOf(4f) }
    val premium by AppSettings.premiumEntitled.collectAsStateWithLifecycle()
    val owner by AppSettings.isOwnerAccount.collectAsStateWithLifecycle()
    val premiumAllowed = premium || owner
    val premiumReleased = rememberPremiumReleaseReached()
    val maxGuests = if (premiumAllowed) 14 else 4
    var membersCanAdd by rememberSaveable { mutableStateOf(true) }
    var durationCode by rememberSaveable { mutableStateOf("24h") }
    LaunchedEffect(maxGuests) { guests = guests.coerceIn(1f, maxGuests.toFloat()) }
    var coverUri by rememberSaveable { mutableStateOf<String?>(null) }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<List<Song>>(emptyList()) }
    var search by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(initialAction == "create") }
    var createdHere by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var removeMember by remember { mutableStateOf<com.music.orb.data.rooms.RoomMember?>(null) }
    var editRemoveCover by rememberSaveable { mutableStateOf(false) }
    var showAllTracks by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> coverUri = uri?.toString() ?: coverUri }
    var suggestions by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loadingSuggestions by remember { mutableStateOf(false) }
    var suggestionsError by remember { mutableStateOf(false) }
    var suggestionAttempt by remember { mutableIntStateOf(0) }
    val radioCache = remember(room?.id) { mutableMapOf<String,List<Song>>() }
    val suggestionSeeds = ((room?.queue.orEmpty().distinctBy { it.artist }.takeLast(3)) + selected.takeLast(3))
        .distinctBy { it.videoId }.takeLast(5)
    val suggestionSeedIds = suggestionSeeds.map { it.videoId }
    val excludedSuggestionIds = (room?.queue.orEmpty()+selected).map { it.videoId }.toSet()

    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = (exception as? RoomException)?.reason ?: "ROOM_NETWORK"
            } finally {
                busy = false
            }
        }
    }
    fun transport(index: Int? = null) = runAction {
        val viewed = requireNotNull(RoomRepository.room.value)
        val playingRoom = RoomRepository.playbackRoom.value
        if (!viewed.host) {
            if(playingRoom?.id==viewed.id) {
                if(com.music.orb.data.rooms.RoomSession.guestPaused.value) controller?.play() else controller?.pause()
            } else {
                RoomRepository.activate()
                com.music.orb.data.rooms.RoomSession.setGuestPaused(false)
                com.music.orb.data.rooms.RoomSession.awaitReady(viewed.id)
            }
            return@runAction
        }
        if (playingRoom?.id == viewed.id && player.isPlaying && index == null) {
            controller?.pause()
            RoomRepository.publish(viewed.id, controller?.currentMediaItemIndex?.coerceAtLeast(0) ?: viewed.index,
                controller?.currentPosition ?: viewed.positionMs, false, viewed.queueVersion)
        } else {
            RoomRepository.activate()
            com.music.orb.data.rooms.RoomSession.awaitReady(viewed.id)
            if (index != null) controller?.seekTo(index, 0)
            controller?.play()
        }
    }
    fun copyCode(value: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.rooms_code),value))
        android.widget.Toast.makeText(context,R.string.rooms_code_copied,android.widget.Toast.LENGTH_SHORT).show()
    }
    fun finishSelection() = runAction {
        val current = requireNotNull(RoomRepository.room.value)
        if (selected.isNotEmpty()) RoomRepository.appendSongs(selected)
        selected = emptyList()
        stage = if (creating) "animation" else "room"
    }

    LaunchedEffect(search, stage) {
        if (stage != "choose") return@LaunchedEffect
        if (search.isBlank()) { searchResults = emptyList(); searching = false; searchError = false; return@LaunchedEffect }
        searching = true; searchError = false
        delay(350)
        val result = YtMusicRepository.search(search.trim(),SearchFilter.SONGS)
        searchResults = result.getOrNull().orEmpty().mapNotNull { (it as? SearchResult.Track)?.song }
            .filter { it.videoId.matches(Regex("[A-Za-z0-9_-]{11}")) }.distinctBy { it.videoId }
        searchError = result.isFailure
        searching = false
    }
    LaunchedEffect(stage, suggestionSeedIds, excludedSuggestionIds, suggestionAttempt) {
        if(stage != "choose") return@LaunchedEffect
        suggestionsError=false
        if(suggestionSeeds.isEmpty()) {
            suggestions=emptyList();loadingSuggestions=false
            return@LaunchedEffect
        }
        loadingSuggestions=true
        suggestions=suggestions.filter { it.videoId !in excludedSuggestionIds }
        try {
            delay(450)
            val batches = kotlinx.coroutines.coroutineScope {
                suggestionSeeds.map { seed -> async {
                    radioCache[seed.videoId] ?: YtMusicRepository.radio(seed.videoId).getOrNull()?.also {
                        radioCache[seed.videoId]=it
                    }.orEmpty()
                } }.awaitAll()
            }
            val mixed = buildList<Song> {
                for(index in 0 until (batches.maxOfOrNull { it.size } ?: 0)) {
                    batches.forEach { batch -> batch.getOrNull(index)?.let { add(it) } }
                }
            }.filter { it.videoId !in excludedSuggestionIds && it.videoId.matches(Regex("[A-Za-z0-9_-]{11}")) }
                .distinctBy { it.videoId }
            val artistCounts=mutableMapOf<String,Int>()
            val varied=mixed.filter { song ->
                val count=artistCounts.getOrDefault(song.artist,0)
                artistCounts[song.artist]=count+1
                count<4
            }
            suggestions=(varied+mixed).distinctBy { it.videoId }.take(25)
            suggestionsError=batches.all { it.isEmpty() }
        } finally { loadingSuggestions=false }
    }
    LaunchedEffect(room?.id) {
        if (room == null && stage in setOf("room","details","invite","animation","join-animation","welcome-animation","choose")) stage = "closed"
    }

    LaunchedEffect(room?.id, stage) {
        val id = room?.id ?: return@LaunchedEffect
        if (stage !in setOf("room", "details")) return@LaunchedEffect
        while (true) {
            // The PlaybackService owns the frequent clock sync. A foreground
            // room sheet only needs periodic membership/cover refreshes.
            delay(if (AppSettings.meteredConnection.value == true) 10_000L else 5_000L)
            try {
                RoomRepository.open(id)
            } catch (expired: RoomException) {
                if (expired.reason == "ROOM_CLOSED" || expired.reason == "ROOM_NOT_MEMBER") {
                    RoomRepository.clearLocal()
                    RoomRepository.issue.value = expired.reason
                    break
                }
            } catch (_: Exception) {
                // Temporary disconnection must not close an otherwise valid room.
            }
        }
    }

    // Joining a room is a compact modal sheet; only the room itself uses the
    // immersive page. Keep the same stage/animation when the join succeeds.
    if (stage == "join") {
        com.music.orb.ui.components.RegisterSheetBackdrop()
        val sheetColor = orbOverlayGlassBaseColor()
        ModalBottomSheet(
            floatingTitle = stringResource(R.string.rooms_join),
            onDismissRequest = { if (!busy) onDismiss() },
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(topStart=36.dp, topEnd=36.dp),
            dragHandle = null,
            navigationBarInsetInContent = true,
        ) {
            com.music.orb.ui.components.OrbGlassSheetSurface(sheetColor, 36.dp) {
                Column(
                    Modifier.fillMaxWidth().imePadding()
                        .padding(horizontal=24.dp, vertical=16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.rooms_enter_code),
                        style=MaterialTheme.typography.bodyLarge,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign=TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    RoomCodeInput(code, { code=it }, enabled=!busy,
                        onSubmit={ if(code.length==6 && !busy) runAction {
                            RoomRepository.join(code);stage="join-animation"
                        } })
                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(roomErrorText(it), color=MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(22.dp))
                    Button(enabled=code.length==6 && !busy,
                        modifier=Modifier.widthIn(max=280.dp).fillMaxWidth().heightIn(min=52.dp),
                        pageColor=MaterialTheme.colorScheme.secondaryContainer,
                        shape=RoundedCornerShape(26.dp),
                        onClick={runAction { RoomRepository.join(code); stage="join-animation" }}) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth=2.dp)
                        Text(stringResource(R.string.rooms_enter))
                    }
                    if(error=="ROOM_ALREADY_JOINED") Spacer(Modifier.height(12.dp))
                    if(error=="ROOM_ALREADY_JOINED") TextButton(enabled=!busy,
                        onClick={runAction { RoomRepository.restore(force=true);stage="room" }}) {
                        Text(stringResource(R.string.rooms_resume))
                    }
                }
            }
        }
    } else RegisterRoomDialog(transferActive = transferActive, sheetOpen = showAllTracks || confirmLeave || removeMember != null, fullWidth = stage in setOf("choose", "room", "details"), onDismiss = { if (!busy) { if(stage=="edit")stage="details" else if(stage=="details")stage="room" else onDismiss() } }) {
        val active = room
        val animationUser = com.music.orb.data.social.SocialRepository.currentUserId()
        val animationPreferences = remember(context) {
            context.getSharedPreferences("orb_room_opening_animations", android.content.Context.MODE_PRIVATE)
        }
        val openingKey = active?.id?.let { "covers:${animationUser.orEmpty()}:$it" }
        val coversAlreadyShown = remember(openingKey) {
            openingKey != null && animationPreferences.getBoolean(openingKey, false)
        }
        if (stage == "join-animation" && active != null) {
            if (coversAlreadyShown) {
                RoomWelcomeAnimation(active) { stage = "room" }
            } else {
                RoomJoinAnimation(active) {
                    openingKey?.let { animationPreferences.edit().putBoolean(it, true).apply() }
                    stage = "welcome-animation"
                }
            }
        } else if (stage == "welcome-animation" && active != null) {
            RoomWelcomeAnimation(active) { stage = "room" }
        } else if (stage == "animation" && active != null) {
            RoomCreationAnimation(active) {
                openingKey?.let { animationPreferences.edit().putBoolean(it, true).apply() }
                stage = "invite"; creating = false
            }
        } else {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).navigationBarsPadding().imePadding().padding(horizontal=if(stage in setOf("choose", "room", "details"))0.dp else 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal=if(stage in setOf("room","choose","details"))13.dp else 0.dp).height(64.dp), verticalAlignment=Alignment.CenterVertically) {
                    androidx.compose.material3.IconButton(enabled=!busy,onClick={ if(stage == "edit") stage="details" else if(stage == "details") stage="room" else onDismiss() }) {
                        Icon(Icons.Rounded.ArrowBack, stringResource(R.string.rooms_back), tint=MaterialTheme.colorScheme.onSurface)
                    }
                    if (stage == "room" && active != null) {
                        Row(Modifier.weight(1f), verticalAlignment=Alignment.CenterVertically) {
                            Text(active.title, style=MaterialTheme.typography.titleLarge,
                                maxLines=1, overflow=TextOverflow.Ellipsis)
                        }
                    } else if (stage == "join") Spacer(Modifier.weight(1f)) else Text(stringResource(when(stage){"create"->R.string.rooms_create;"join"->R.string.rooms_join;"choose"->R.string.rooms_choose;"invite"->R.string.rooms_invite;"details"->R.string.rooms_details;"edit"->R.string.rooms_edit;else->R.string.rooms_title}),style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                    if (active?.host == true && stage == "details") IconButton(enabled=!busy,onClick={
                        title=active.title;membersCanAdd=active.membersCanAdd;durationCode=active.durationCode;coverUri=null;editRemoveCover=false;stage="edit"
                    }) { Icon(Icons.Rounded.Edit,stringResource(R.string.rooms_edit)) }
                    if (active != null && stage == "room") IconButton(onClick={stage="invite"}) { Icon(Icons.Rounded.Share,stringResource(R.string.rooms_invite)) }
                }
                if (error != null) Text(roomErrorText(error),color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(vertical=8.dp))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when(stage) {
                    "create" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(20.dp))
                        RoomArtwork(coverUri,Modifier.size(180.dp).clickable { picker.launch("image/*") })
                        Spacer(Modifier.height(18.dp))
                        TextButton(onClick={picker.launch("image/*")},enabled=!busy) { Text(stringResource(R.string.rooms_choose_cover)) }
                        if(coverUri!=null) Spacer(Modifier.height(8.dp))
                        if(coverUri!=null) TextButton(onClick={coverUri=null},enabled=!busy) { Text(stringResource(R.string.rooms_remove_cover)) }
                        OutlinedTextField(title,{ title=it.take(80) },label={Text(stringResource(R.string.rooms_name))},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                        Spacer(Modifier.height(24.dp))
                        Text(stringResource(R.string.rooms_member_limit,guests.roundToInt()+1),style=MaterialTheme.typography.titleMedium)
                        Slider(guests,{guests=it},valueRange=1f..maxGuests.toFloat(),steps=maxGuests-2,enabled=!busy)
                        Text(stringResource(R.string.rooms_total_includes_host),style=MaterialTheme.typography.bodyMedium)
                        if (!premiumAllowed && premiumReleased) {
                            Row(Modifier.alpha(0.45f).padding(top=6.dp), verticalAlignment=Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Lock, contentDescription = null, modifier=Modifier.size(16.dp))
                                Text(stringResource(R.string.rooms_premium_capacity_locked),
                                    style=MaterialTheme.typography.bodySmall, modifier=Modifier.padding(start=6.dp))
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        RoomDurationChoice(durationCode, { durationCode = it }, premiumAllowed, !busy)
                        Spacer(Modifier.height(20.dp))
                        Text(stringResource(R.string.rooms_music_permissions),style=MaterialTheme.typography.titleMedium)
                        if(premiumAllowed || premiumReleased) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .alpha(if (premiumAllowed) 1f else 0.45f)
                                    .clickable(enabled = !busy && premiumAllowed) { membersCanAdd = false },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected=!membersCanAdd && premiumAllowed,
                                    onClick={membersCanAdd=false},enabled=!busy && premiumAllowed)
                                Text(stringResource(R.string.rooms_only_host))
                                if (!premiumAllowed) Icon(Icons.Rounded.Lock,
                                    contentDescription = stringResource(R.string.premium_locked_accessibility),
                                    modifier = Modifier.padding(start=8.dp).size(18.dp))
                            }
                            Row(Modifier.fillMaxWidth().clickable(enabled=!busy){membersCanAdd=true},verticalAlignment=Alignment.CenterVertically) {
                                RadioButton(selected=membersCanAdd,onClick={membersCanAdd=true},enabled=!busy)
                                Text(stringResource(R.string.rooms_everyone))
                            }
                        } else Text(stringResource(R.string.rooms_everyone),style=MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(24.dp))
                        Button(enabled=title.isNotBlank()&&!busy,
                                modifier=Modifier.widthIn(max=280.dp).heightIn(min=52.dp),
                                pageColor=MaterialTheme.colorScheme.secondaryContainer,
                                shape=RoundedCornerShape(26.dp),onClick={runAction {
                            if (!createdHere) {
                                RoomRepository.create(title.trim(), guests.roundToInt(), !premiumAllowed || membersCanAdd, durationCode)
                                createdHere = true
                            }
                            if(coverUri!=null) RoomRepository.uploadCover(context,Uri.parse(coverUri))
                            stage="choose"
                        }}) { Text(stringResource(R.string.rooms_create)) }
                        if(createdHere && active?.host==true) Spacer(Modifier.height(12.dp))
                        if(createdHere && active?.host==true) TextButton(enabled=!busy,onClick={stage="choose"}) { Text(stringResource(R.string.rooms_continue_without_cover)) }
                        if(error=="ROOM_ALREADY_JOINED") Spacer(Modifier.height(12.dp))
                        if(error=="ROOM_ALREADY_JOINED") TextButton(enabled=!busy,onClick={runAction{RoomRepository.restore(force=true);stage="room"}}) {Text(stringResource(R.string.rooms_resume))}
                    }
                    "choose" -> {
                        OutlinedTextField(search,{search=it},label={Text(stringResource(R.string.rooms_search))},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp))
                        if(selected.isNotEmpty()) LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            items(selected,key={it.videoId}) { song ->
                                InputChip(selected=true,onClick={selected=selected.filterNot { it.videoId==song.videoId }},
                                    label={Text(song.title,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=180.dp))},
                                    trailingIcon={Icon(Icons.Rounded.Check,null)})
                            }
                        }
                        if(searching || (loadingSuggestions && search.isBlank())) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp))
                        val tracks=if(search.isBlank()) suggestions else searchResults
                        if(tracks.isNotEmpty()) {
                            RoomSongCarousel(tracks,selected.map { it.videoId }.toSet(),Modifier.weight(1f)) { song->
                                com.music.orb.ui.components.OrbHaptics.perform(context,com.music.orb.ui.components.OrbHaptics.Kind.TOGGLE)
                                selected=if(selected.any{it.videoId==song.videoId})selected.filterNot{it.videoId==song.videoId}
                                else if(selected.size+(active?.queue?.size?:0)<100 && active?.queue?.none { it.videoId==song.videoId } != false)selected+song else selected
                                search=""
                            }
                        } else Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(horizontal=20.dp)) {
                                Text(stringResource(when {
                                    search.isBlank() && suggestionSeeds.isEmpty() -> R.string.rooms_choose_first_track
                                    searchError || (search.isBlank() && suggestionsError) -> R.string.rooms_search_error
                                    else -> R.string.rooms_no_suggestions
                                }),textAlign=TextAlign.Center)
                                if(search.isBlank() && suggestionsError) TextButton(onClick={suggestionAttempt++}) { Text(stringResource(R.string.rooms_retry_suggestions)) }
                            }
                        }
                        Button(enabled=!busy&&selected.isNotEmpty(),modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp),onClick={finishSelection()}) {Text(stringResource(R.string.rooms_continue_tracks,selected.size))}
                        Spacer(Modifier.height(12.dp))
                        TextButton(enabled=!busy,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp),onClick={selected=emptyList();finishSelection()}) {Text(stringResource(R.string.rooms_skip))}
                        Spacer(Modifier.height(16.dp))
                    }
                    "invite" -> if(active!=null) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(20.dp));RoomArtwork(active.coverUrl,Modifier.size(180.dp))
                        Spacer(Modifier.height(20.dp));Text(active.title,style=MaterialTheme.typography.headlineSmall,textAlign=TextAlign.Center)
                        Spacer(Modifier.height(28.dp));Text(stringResource(R.string.rooms_code),style=MaterialTheme.typography.titleMedium)
                        Text(active.code,style=MaterialTheme.typography.displayMedium)
                        Text(stringResource(R.string.rooms_guest_limit,active.maxGuests),style=MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick={copyCode(active.code)},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.rooms_copy))}
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick={
                            val text=context.getString(R.string.rooms_share_text,active.title,active.code)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),context.getString(R.string.rooms_share)))
                        },modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.rooms_share))}
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick={stage="room"},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.rooms_enter))}
                        Spacer(Modifier.height(24.dp))
                    }
                    "room" -> if (active != null) {
                        RoomOverview(
                            room = active,
                            playing = RoomRepository.playbackRoom.value?.id == active.id && player.isPlaying,
                            following = RoomRepository.playbackRoom.value?.id == active.id,
                            enabled = !busy,
                            issue = if (!active.host && !active.hostOnline) "ROOM_HOST_OFFLINE"
                                else issue?.takeUnless { it == "ROOM_HOST_OFFLINE" },
                            onPlayPause = { transport() },
                            onDetails = { stage = "details" },
                            onAllTracks = { showAllTracks = true },
                            onAdd = { creating=false; selected=emptyList(); search=""; stage="choose" },
                            onTrack = { index -> transport(index) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    "edit" -> if(active?.host == true) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally) {
                            RoomArtwork(if(editRemoveCover)null else coverUri ?: active.coverUrl,Modifier.size(160.dp))
                            Spacer(Modifier.height(18.dp))
                            TextButton(enabled=!busy,onClick={editRemoveCover=false;picker.launch("image/*")}) { Text(stringResource(R.string.rooms_choose_cover)) }
                            Spacer(Modifier.height(8.dp))
                            TextButton(enabled=!busy,onClick={coverUri=null;editRemoveCover=true}) { Text(stringResource(R.string.rooms_remove_cover)) }
                            OutlinedTextField(title,{title=it.take(80)},label={Text(stringResource(R.string.rooms_name))},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                            Spacer(Modifier.height(22.dp))
                            RoomDurationChoice(durationCode, { durationCode = it }, premiumAllowed, !busy)
                            Spacer(Modifier.height(20.dp))
                            Text(stringResource(R.string.rooms_music_permissions),style=MaterialTheme.typography.titleMedium)
                            if(premiumAllowed || premiumReleased) {
                                Row(Modifier.fillMaxWidth().alpha(if (premiumAllowed) 1f else 0.45f),verticalAlignment=Alignment.CenterVertically) {
                                    RadioButton(selected=!membersCanAdd && premiumAllowed,onClick={membersCanAdd=false},enabled=!busy && premiumAllowed)
                                    Text(stringResource(R.string.rooms_only_host))
                                    if (!premiumAllowed) Icon(Icons.Rounded.Lock,
                                        contentDescription = stringResource(R.string.premium_locked_accessibility),
                                        modifier = Modifier.padding(start=8.dp).size(18.dp))
                                }
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                    RadioButton(selected=membersCanAdd,onClick={membersCanAdd=true},enabled=!busy)
                                    Text(stringResource(R.string.rooms_everyone))
                                }
                            } else Text(stringResource(R.string.rooms_everyone))
                            Spacer(Modifier.height(22.dp))
                            Text(stringResource(R.string.rooms_manage_members),style=MaterialTheme.typography.titleMedium)
                            active.members.filter { !it.host }.forEach { member ->
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                    RoomMemberAvatar(member,Modifier.size(44.dp))
                                    Text(member.name,modifier=Modifier.weight(1f).padding(horizontal=12.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
                                    RoomPlainTextAction(label=stringResource(R.string.rooms_remove), enabled=!busy, onClick={removeMember=member})
                                }
                            }
                            Spacer(Modifier.height(22.dp))
                            Button(enabled=title.isNotBlank()&&!busy,
                                modifier=Modifier.widthIn(max=280.dp).heightIn(min=52.dp),
                                pageColor=MaterialTheme.colorScheme.secondaryContainer,
                                shape=RoundedCornerShape(26.dp),onClick={runAction {
                                RoomRepository.edit(title.trim(),!premiumAllowed||membersCanAdd,durationCode)
                                if(editRemoveCover)RoomRepository.removeCover() else coverUri?.let { RoomRepository.uploadCover(context,Uri.parse(it)) }
                                RoomRepository.loadRooms();stage="details"
                            }}) { Text(stringResource(R.string.rooms_save)) }
                        }
                    }
                    "details" -> if(active!=null) {
                        RoomDetails(active, !busy, Modifier.weight(1f),
                            onInvite={stage="invite"},
                            onAdd={creating=false;selected=emptyList();search="";stage="choose"},
                            onTrack={transport(it)},
                            onAllTracks={showAllTracks=true},
                            onLeave={confirmLeave=true},
                        )
                    }
                    "closed" -> Column(Modifier.weight(1f),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(roomErrorText(issue?:"ROOM_CLOSED"),style=MaterialTheme.typography.headlineSmall)
                        TextButton(onClick=onDismiss){Text(stringResource(R.string.rooms_back))}
                    }
                }
            }
        }
        if (showAllTracks && active != null) {
            com.music.orb.ui.components.RegisterSheetBackdrop()
            val sheetGlassColor = orbOverlayGlassBaseColor()
            ModalBottomSheet(
                floatingTitle = null,
                onDismissRequest={showAllTracks=false},
                containerColor=Color.Transparent,
                shape=RoundedCornerShape(topStart=36.dp, topEnd=36.dp),
                dragHandle=null,
                navigationBarInsetInContent = true,
        ) {
                com.music.orb.ui.components.OrbGlassSheetSurface(
                    sheetGlassColor, 36.dp,
                    modifier = Modifier.clip(RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f)),
                ) {
                Text(stringResource(R.string.rooms_tracks), style=MaterialTheme.typography.headlineSmall,
                    modifier=Modifier.padding(horizontal=20.dp, vertical=12.dp))
                if (active.queue.isEmpty()) Text(stringResource(R.string.rooms_empty_queue), modifier=Modifier.padding(20.dp))
                val queueMotionState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                LazyVerticalGrid(columns=GridCells.Fixed(3), state=queueMotionState,
                    modifier=Modifier.fillMaxWidth().heightIn(max=600.dp),
                    contentPadding=PaddingValues(16.dp), horizontalArrangement=Arrangement.spacedBy(12.dp), verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    gridItemsIndexed(active.queue, key={index,song->"$index:${song.videoId}"}) { index,song ->
                        Column(Modifier.orbSheetProgressiveGridItem(queueMotionState, index)) {
                            RoomTrackCard(song, Modifier.fillMaxWidth(), active.host && !busy) { transport(index) }
                            if(active.membersCanAdd) active.trackAuthors[song.videoId]?.let { author ->
                                Text(stringResource(R.string.rooms_added_by,author.trim().split(Regex("\\s+")).firstOrNull().orEmpty()),
                                    maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if(active.host) {
                                RoomPlainTextAction(
                                    label = stringResource(R.string.rooms_remove),
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { runAction { RoomRepository.setQueue(active.queue.filterIndexed { i, _ -> i != index }) } },
                                )
                            }
                        }
                    }
                    if (active.canAdd) item {
                        RoomAddCard(Modifier.fillMaxWidth(), !busy) { showAllTracks=false;creating=false;selected=emptyList();search="";stage="choose" }
                    }
                }
                }
            }
        }
        removeMember?.let { member ->
            AlertDialog(onDismissRequest={if(!busy)removeMember=null},
                modifier=Modifier.orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius=28.dp), containerColor=Color.Transparent,
                tonalElevation=0.dp, shape=RoundedCornerShape(28.dp), title={Text(stringResource(R.string.rooms_remove_member))},
                text={Text(stringResource(R.string.rooms_remove_member_notice,member.name))},
                confirmButton={TextButton(enabled=!busy,onClick={runAction {RoomRepository.removeMember(member.id);removeMember=null}}) { Text(stringResource(R.string.rooms_remove)) }},
                dismissButton={TextButton(enabled=!busy,onClick={removeMember=null}) { Text(stringResource(R.string.rooms_cancel)) }})
        }
        if(confirmLeave) AlertDialog(onDismissRequest={if(!busy)confirmLeave=false},
            modifier=Modifier.orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius=28.dp), containerColor=Color.Transparent,
            tonalElevation=0.dp, shape=RoundedCornerShape(28.dp), title={Text(stringResource(if(active?.host==true)R.string.rooms_end else R.string.rooms_leave))},
            text={Text(stringResource(if(active?.host==true)R.string.rooms_end_notice else R.string.rooms_leave_notice))},
            confirmButton={TextButton(enabled=!busy,onClick={runAction{val endingActive = RoomRepository.playbackRoom.value?.id == active?.id; RoomRepository.leave();if(endingActive) controller?.pause();confirmLeave=false;onDismiss()}}){Text(stringResource(R.string.rooms_confirm))}},
            dismissButton={TextButton(enabled=!busy,onClick={confirmLeave=false}){Text(stringResource(R.string.rooms_cancel))}})
    }
}


private val roomDurationCodes = listOf("24h", "3d", "5d", "7d", "15d", "indefinite")

@Composable
private fun RoomDurationChoice(
    selected: String,
    onSelect: (String) -> Unit,
    premiumAllowed: Boolean,
    enabled: Boolean,
) {
    val labels = listOf(
        stringResource(R.string.rooms_duration_24h),
        stringResource(R.string.rooms_duration_3d),
        stringResource(R.string.rooms_duration_5d),
        stringResource(R.string.rooms_duration_7d),
        stringResource(R.string.rooms_duration_15d),
        stringResource(R.string.rooms_duration_indefinite),
    )
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.rooms_duration_title), style = MaterialTheme.typography.titleMedium)
        roomDurationCodes.forEachIndexed { index, code ->
            val allowed = enabled && (code != "indefinite" || premiumAllowed)
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = allowed) { onSelect(code) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == code,
                    onClick = { onSelect(code) },
                    enabled = allowed,
                )
                Text(labels[index], modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (allowed) 1f else 0.42f))
                if (code == "indefinite" && !premiumAllowed) {
                    Icon(Icons.Rounded.Lock, contentDescription = stringResource(R.string.premium_locked_accessibility),
                        modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Premium", modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RoomTimeRemaining(room: ListeningRoom) {
    val until = if (room.expiresAtMs < 0L && room.createdAtMs > 0L) {
        // Older backend revisions don't return the lifetime yet. They always
        // used 24 hours; don't mistake missing metadata for Premium unlimited.
        room.createdAtMs + 86_400_000L
    } else room.expiresAtMs
    if (until < 0L) return
    if (until == 0L) {
        Text(stringResource(R.string.rooms_duration_no_expiry),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var remainingMs by remember(room.id, until) { mutableLongStateOf((until - room.serverMs).coerceAtLeast(0L)) }
    LaunchedEffect(room.id, until, room.serverMs) {
        val start = android.os.SystemClock.elapsedRealtime()
        val initial = (until - room.serverMs).coerceAtLeast(0L)
        while (true) {
            remainingMs = (initial - (android.os.SystemClock.elapsedRealtime() - start)).coerceAtLeast(0L)
            delay(30_000L)
        }
    }
    val days = remainingMs / 86_400_000L
    val hours = (remainingMs % 86_400_000L) / 3_600_000L
    val minutes = (remainingMs % 3_600_000L) / 60_000L
    val label = when {
        remainingMs == 0L -> stringResource(R.string.rooms_duration_expired)
        days > 0L -> stringResource(R.string.rooms_duration_remaining_days, days, hours)
        else -> stringResource(R.string.rooms_duration_remaining_hours, hours, minutes)
    }
    Text(label, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RoomDetails(room: ListeningRoom, enabled: Boolean, modifier: Modifier,
    onInvite: () -> Unit, onAdd: () -> Unit, onTrack: (Int) -> Unit, onAllTracks: () -> Unit, onLeave: () -> Unit) {
    LazyColumn(modifier.fillMaxWidth(),contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                if(room.coverUrl != null) RoomArtwork(room.coverUrl,Modifier.size(180.dp))
                Spacer(Modifier.height(12.dp))
                Text(room.title,style=MaterialTheme.typography.headlineSmall,textAlign=TextAlign.Center)
                if(room.createdAtMs>0) Text(stringResource(R.string.rooms_created_on,
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(java.util.Date(room.createdAtMs))),
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                RoomTimeRemaining(room)
                RoomPlainTextAction(label=stringResource(R.string.rooms_invite), onClick=onInvite)
            }
        }
        item { Text(stringResource(R.string.rooms_participant_count,room.members.size,room.maxGuests+1),style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(horizontal=20.dp)) }
        item {
            LazyRow(modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                items(room.members,key={it.id}) { member ->
                    Column(Modifier.width(72.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        RoomMemberAvatar(member,Modifier.size(52.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(member.name.trim().split(Regex("\\s+")).firstOrNull().orEmpty(),
                            maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item {
            Column {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.rooms_tracks),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                RoomPlainTextAction(label=stringResource(R.string.rooms_view_all), onClick=onAllTracks)
            }
            }
        }
        if(room.queue.isEmpty()) item { Text(stringResource(R.string.rooms_empty_queue),modifier=Modifier.padding(horizontal=20.dp)) }
        item {
            LazyRow(modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                itemsIndexed(room.queue.take(15),key={index,song->"$index:${song.videoId}"}) { index,song ->
                    RoomTrackCard(song,Modifier.width(84.dp),enabled&&room.host) { onTrack(index) }
                }
                if(room.canAdd) item { RoomAddCard(Modifier.width(84.dp),enabled,onAdd) }
            }
        }
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) {
                androidx.compose.material3.TextButton(onClick=onLeave, enabled=enabled) {
                    Text(stringResource(if(room.host)R.string.rooms_end else R.string.rooms_leave),
                        color=MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun RoomOverview(
    room: ListeningRoom, playing: Boolean, following: Boolean, enabled: Boolean, issue: String?,
    onPlayPause: () -> Unit, onDetails: () -> Unit, onAllTracks: () -> Unit,
    onAdd: () -> Unit, onTrack: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    val configuration=androidx.compose.ui.platform.LocalConfiguration.current
    val songCardWidth=if(configuration.smallestScreenWidthDp>=600 &&
        configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE) 64.dp else 84.dp
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp), horizontalArrangement=Arrangement.Center, verticalAlignment=Alignment.CenterVertically) {
            if (room.playbackElsewhere) {
                Button(onClick={},enabled=false,modifier=Modifier.height(46.dp)) {
                    Icon(if(room.playing)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.rooms_other_device_control))
                }
            } else if (!room.host && !following) Button(onClick = com.music.orb.ui.components.OrbHaptics.transportClick(onPlayPause),enabled=enabled && room.queue.isNotEmpty(),modifier=Modifier.height(46.dp)) {
                Icon(Icons.Rounded.PlayArrow,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.rooms_join_listening))
            } else FilledIconButton(onClick = com.music.orb.ui.components.OrbHaptics.transportClick(onPlayPause), enabled=enabled && room.queue.isNotEmpty(),
                modifier=Modifier.size(46.dp), shape=RoundedCornerShape(18.dp),
                pageColor=MaterialTheme.colorScheme.secondaryContainer) {
                Icon(if(playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if(playing)R.string.rooms_pause else R.string.rooms_play))
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick=onDetails, enabled=enabled, modifier=Modifier.height(46.dp),
                pageColor=MaterialTheme.colorScheme.surfaceContainerHigh) {
                Icon(Icons.Rounded.Info, null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.rooms_details))
            }
        }
        if (issue != null) Text(roomErrorText(issue), color=MaterialTheme.colorScheme.error, modifier=Modifier.padding(horizontal=20.dp).padding(top=8.dp))
        if (!room.host && !room.playbackElsewhere) Text(stringResource(when {
            following -> R.string.rooms_following_live
            room.playing -> R.string.rooms_live_preview
            else -> R.string.rooms_paused_preview
        }), style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(top=12.dp))
        RoomFloatingMembers(room, Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp), verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(R.string.rooms_tracks), style=MaterialTheme.typography.titleMedium, modifier=Modifier.weight(1f))
            RoomPlainTextAction(label=stringResource(R.string.rooms_view_all), onClick=onAllTracks)
        }
        LazyRow(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp), contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=16.dp)) {
            itemsIndexed(room.queue.take(15), key={index,song->"$index:${song.videoId}"}) { index,song ->
                RoomTrackCard(song, Modifier.width(songCardWidth), room.host && enabled) { onTrack(index) }
            }
            item(key="add-tracks") { RoomAddCard(Modifier.width(songCardWidth), room.canAdd && enabled, onAdd) }
        }
        if (room.queue.isEmpty()) Text(stringResource(R.string.rooms_empty_queue), style=MaterialTheme.typography.bodyMedium,
            modifier=Modifier.padding(horizontal=20.dp).padding(bottom=12.dp), color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RoomFloatingMembers(room: ListeningRoom, modifier: Modifier) {
    val bubbles=remember(room.id) {mutableStateMapOf<String,String>()}
    val seen=remember(room.id) {room.additions.filter {room.serverMs-it.atMs>3000L}.map {it.id}.toMutableSet()}
    val bubbleScope=rememberCoroutineScope()
    LaunchedEffect(room.id,room.additions.map {it.id}) {
        room.additions.filter {it.id !in seen}.forEach { addition ->
            seen.add(addition.id)
            bubbles[addition.userId]=addition.id
            bubbleScope.launch {
                delay(3000)
                if(bubbles[addition.userId]==addition.id)bubbles.remove(addition.userId)
            }
        }
    }
    val transition = rememberInfiniteTransition(label="room-members")
    val phase by transition.animateFloat(0f, 6.283185f,
        animationSpec=infiniteRepeatable(tween(7000, easing=LinearEasing)), label="floating-phase")
    BoxWithConstraints(modifier, contentAlignment=Alignment.Center) {
        val areaWidth = maxWidth
        val areaHeight = maxHeight
        val centerSize = minOf(areaWidth*.56f, areaHeight*.58f, 240.dp)
        val radiusX = minOf(areaWidth*.38f, ((areaWidth-64.dp)/2).coerceAtLeast(0.dp))
        val radiusY = minOf(areaHeight*.34f, ((areaHeight-72.dp)/2).coerceAtLeast(0.dp))
        com.music.orb.ui.components.RoomLivingOrb(Modifier.size(centerSize))
        room.members.forEachIndexed { index, member ->
            val angle = -1.570796f + index*6.283185f/room.members.size.coerceAtLeast(1)
            Column(horizontalAlignment=Alignment.CenterHorizontally,
                modifier=Modifier.width(64.dp).zIndex(if(bubbles.containsKey(member.id))2f else 0f).graphicsLayer {
                    translationX=cos(angle)*radiusX.toPx()+sin(phase+index)*5.dp.toPx()
                    translationY=sin(angle)*radiusY.toPx()+cos(phase+index)*7.dp.toPx()
                }) {
                Box(Modifier.size(44.dp),contentAlignment=Alignment.Center) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),contentAlignment=Alignment.Center) {
                        if(member.avatar != null) AsyncImage(member.avatar, member.name, contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
                        else Text(member.name.take(1).uppercase(Locale.ROOT), style=MaterialTheme.typography.titleMedium)
                    }
                    val memberX = areaWidth / 2 + radiusX * cos(angle)
                    val opensRight = areaWidth - memberX >= memberX
                    val bubbleWidth = minOf(180.dp,
                        (maxOf(memberX,areaWidth-memberX)-32.dp).coerceAtLeast(100.dp))
                    androidx.compose.animation.AnimatedVisibility(visible=bubbles.containsKey(member.id),
                        modifier=Modifier.align(Alignment.BottomStart)
                            .offset(x=if(opensRight)22.dp else 22.dp-bubbleWidth,y=(-46).dp)
                            .wrapContentSize(Alignment.BottomStart,unbounded=true)
                            .size(bubbleWidth,82.dp),
                        enter=androidx.compose.animation.fadeIn(tween(160))+androidx.compose.animation.scaleIn(tween(260),initialScale=.35f),
                        exit=androidx.compose.animation.fadeOut(tween(180))+androidx.compose.animation.scaleOut(tween(180),targetScale=.7f)) {
                        val bubbleColor=MaterialTheme.colorScheme.surfaceContainerHighest
                        Box(Modifier.fillMaxSize()) {
                            Surface(modifier=Modifier.fillMaxWidth().height(60.dp),shape=RoundedCornerShape(30.dp),
                                color=bubbleColor,contentColor=MaterialTheme.colorScheme.onSurface,shadowElevation=3.dp) {
                                Box(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=10.dp),contentAlignment=Alignment.Center) {
                                    Text(stringResource(R.string.rooms_added_bubble),style=MaterialTheme.typography.bodySmall,
                                        textAlign=TextAlign.Center,maxLines=2)
                                }
                            }
                            androidx.compose.foundation.Canvas(Modifier.align(if(opensRight)Alignment.BottomStart else Alignment.BottomEnd)
                                .size(30.dp,26.dp)) {
                                fun tailX(x:Float) = if(opensRight)x.dp.toPx() else size.width-x.dp.toPx()
                                drawCircle(bubbleColor,radius=8.dp.toPx(),
                                    center=androidx.compose.ui.geometry.Offset(tailX(18f),7.dp.toPx()))
                                drawCircle(bubbleColor,radius=4.dp.toPx(),
                                    center=androidx.compose.ui.geometry.Offset(tailX(5f),20.dp.toPx()))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomTrackCard(song: Song, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Column(modifier.clickable(enabled=enabled, onClick=onClick)) {
        RoomArtwork(song.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Text(song.title, maxLines=1, overflow=TextOverflow.Ellipsis, style=MaterialTheme.typography.titleSmall)
        Text(song.artist, maxLines=1, overflow=TextOverflow.Ellipsis, style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RoomAddCard(modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Column(modifier.clickable(enabled=enabled, onClick=onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),contentAlignment=Alignment.Center) {
            Icon(Icons.Rounded.Add, stringResource(R.string.rooms_add_tracks), modifier=Modifier.size(32.dp),
                tint=MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha=if(enabled)1f else .4f))
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.rooms_add_short), style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
private fun RegisterRoomDialog(fullWidth:Boolean=false,transferActive:Boolean=false,sheetOpen:Boolean=false,onDismiss:()->Unit,content:@Composable ()->Unit) {
    com.music.orb.ui.components.RegisterSheetBackdrop()
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(
            Modifier.fillMaxSize()
                .then(if(transferActive || sheetOpen) Modifier.blur(36.4.dp,
                    androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded) else Modifier),
            color=MaterialTheme.colorScheme.background,
            contentColor=MaterialTheme.colorScheme.onBackground,
        ) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                Box((if(fullWidth)Modifier else Modifier.widthIn(max=960.dp)).fillMaxHeight().fillMaxWidth()) {content()}
            }
        }
    }
}

@Composable
private fun RoomArtwork(model:Any?,modifier:Modifier=Modifier) {
    Box(modifier.clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer,MaterialTheme.colorScheme.tertiaryContainer))),contentAlignment=Alignment.Center) {
        if(model!=null)AsyncImage(model,null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote,null,modifier=Modifier.fillMaxSize(.42f),tint=MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomSongCarousel(songs:List<Song>,selected:Set<String>,modifier:Modifier,onSelect:(Song)->Unit) {
    val state=rememberLazyListState()
    val centerIndex by remember(state,songs) {derivedStateOf {
        val layout=state.layoutInfo
        val center=(layout.viewportStartOffset+layout.viewportEndOffset)/2
        layout.visibleItemsInfo.minByOrNull{abs(it.offset+it.size/2-center)}?.index?.coerceIn(0,songs.lastIndex)?:0
    }}
    val context = LocalContext.current
    LaunchedEffect(state, songs) {
        var previous: String? = null
        snapshotFlow { songs.getOrNull(centerIndex)?.videoId to state.isScrollInProgress }.collect { (id, scrolling) ->
            if(scrolling && previous!=null && id!=null && previous!=id) {
                com.music.orb.ui.components.OrbHaptics.perform(context,com.music.orb.ui.components.OrbHaptics.Kind.NAVIGATION)
            }
            previous=id
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
        val carouselWidth = maxWidth
        val coverWidth=minOf(carouselWidth*.56f,240.dp)
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Text(songs[centerIndex].title,style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center,modifier=Modifier.padding(horizontal=12.dp))
            Text(songs[centerIndex].artist,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.height(24.dp))
            LazyRow(modifier=Modifier.fillMaxWidth(),state=state,flingBehavior=rememberSnapFlingBehavior(lazyListState=state),contentPadding=PaddingValues(horizontal=(carouselWidth-coverWidth)/2),horizontalArrangement=Arrangement.spacedBy((-12).dp)) {
                itemsIndexed(songs,key={_,song->song.videoId}) {index,song->
                    Box(Modifier.width(coverWidth).height(coverWidth+16.dp).graphicsLayer {
                        val info=state.layoutInfo.visibleItemsInfo.firstOrNull{it.index==index}
                        val center=(state.layoutInfo.viewportStartOffset+state.layoutInfo.viewportEndOffset)/2f
                        val distance=if(info==null)0f else ((info.offset+info.size/2f-center)/info.size).coerceIn(-2f,2f)
                        rotationY=-distance*28f;cameraDistance=36.dp.toPx()
                        val scale=1f-.12f*abs(distance).coerceAtMost(2f)
                        scaleX=scale;scaleY=scale;alpha=1f-.22f*abs(distance).coerceAtMost(2f)
                    }.clickable{onSelect(song)}) {
                        RoomArtwork(song.thumbnailUrl,Modifier.size(coverWidth))
                        if(song.videoId in selected) Surface(Modifier.align(Alignment.TopEnd).padding(12.dp),shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.primary) {
                            Icon(Icons.Rounded.Check,null,modifier=Modifier.padding(8.dp),tint=MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomCodeInput(code: String, onCodeChange: (String) -> Unit, enabled: Boolean, onSubmit: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = code,
        onValueChange = { onCodeChange(it.uppercase(Locale.ROOT).filter { c -> c in 'A'..'Z' || c in '0'..'9' }.take(6)) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii, autoCorrect = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()
            .focusRequester(focusRequester).onFocusChanged { focused = it.isFocused },
        decorationBox = { innerTextField ->
            Box {
                Box(Modifier.size(1.dp).graphicsLayer { alpha = 0f }) { innerTextField() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { index ->
                        val active = focused && index == code.length.coerceAtMost(5)
                        val shape = RoundedCornerShape(16.dp)
                        Box(
                            Modifier.weight(1f).aspectRatio(.92f).clip(shape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(if (active) 2.dp else 1.dp,
                                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                                .clickable(enabled = enabled) { focusRequester.requestFocus(); keyboard?.show() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(code.getOrNull(index)?.toString().orEmpty(), style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                            if (active && code.length < 6) Box(Modifier.size(2.dp, 24.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)))
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun RoomWelcomeAnimation(room: ListeningRoom, onFinished: () -> Unit) {
    val context = LocalContext.current
    val currentUser = com.music.orb.data.social.SocialRepository.currentUserId()
    val self = room.members.firstOrNull { it.id == currentUser }
    val others = room.members.filter { it.id != currentUser }
    val progress = remember(room.id, currentUser) { Animatable(0f) }
    val finish by rememberUpdatedState(onFinished)
    val sound = remember(context) { RoomWelcomeSound(context.applicationContext) }
    DisposableEffect(sound) { onDispose { sound.release() } }
    LaunchedEffect(room.id, currentUser) {
        sound.play()
        kotlinx.coroutines.coroutineScope {
            val arrivals = launch {
                var nextMember = 0
                snapshotFlow { progress.value }.collect { p ->
                    while (nextMember < others.size && p >= .08f + nextMember*.012f + .36f) {
                        com.music.orb.ui.components.OrbHaptics.roomArtworkTick(context)
                        nextMember++
                        // Preserve separate short pulses even when a frame crosses multiple arrivals.
                        delay(20)
                    }
                }
            }
            progress.animateTo(1f, tween(3800, easing = LinearEasing))
            arrivals.cancel()
        }
        finish()
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) {
        val width = maxWidth
        val height = maxHeight
        val p = progress.value
        val collapse = FastOutSlowInEasing.transform(((p-.80f)/.18f).coerceIn(0f,1f))
        val fade = 1f-((p-.96f)/.04f).coerceIn(0f,1f)
        val photoSize = minOf(width*.3f,height*.22f,140.dp)
        val radius = minOf(width*.34f,height*.23f,180.dp)
        val memberSize = minOf(50.dp, radius * (6.283185f / others.size.coerceAtLeast(1)) * .74f)
        val lastArrival = if(others.isEmpty()) .44f else .44f+(others.size-1)*.012f
        // The orbit starts only after the final photo has landed.
        val orbit = ((p-lastArrival)/(.80f-lastArrival).coerceAtLeast(.01f)).coerceIn(0f,1f)
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha=fade
            translationY=-(height*.06f).toPx()
        },contentAlignment=Alignment.Center) {
            RoomMemberAvatar(self,Modifier.size(photoSize).graphicsLayer {
                val reveal=FastOutSlowInEasing.transform((p/.22f).coerceIn(0f,1f))
                scaleX=(.65f+.35f*reveal)*(1f-collapse);scaleY=scaleX;alpha=reveal
            })
            others.forEachIndexed { index, member ->
                val angle=-1.570796f+index*6.283185f/others.size.coerceAtLeast(1)+orbit*2.1f
                val start=.08f+index*.012f
                val fall=((p-start)/.36f).coerceIn(0f,1f)
                val arrival=FastOutSlowInEasing.transform(fall)
                RoomMemberAvatar(member,Modifier.size(memberSize).graphicsLayer {
                    val ringX=cos(angle)*radius.toPx()
                    val ringY=sin(angle)*radius.toPx()
                    val dropY=-height.toPx()*.62f-memberSize.toPx()
                    translationX=(ringX+(1f-arrival)*sin(index.toFloat())*width.toPx()*.12f)*(1f-collapse)
                    translationY=(dropY+(ringY-dropY)*arrival)*(1f-collapse)
                    rotationZ=(1f-arrival)*sin(index.toFloat())*20f
                    scaleX=(1.35f-.35f*arrival)*(1f-collapse);scaleY=scaleX
                    alpha=(fall/.12f).coerceIn(0f,1f)
                })
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(horizontal=24.dp).padding(bottom=height*.14f).graphicsLayer {
            alpha=((p-.44f)/.18f).coerceIn(0f,1f)*fade*(1f-collapse)
            translationY=(1f-((p-.44f)/.18f).coerceIn(0f,1f))*16.dp.toPx()
        },horizontalAlignment=Alignment.CenterHorizontally) {
            Text(stringResource(R.string.rooms_welcome_member,self?.name.orEmpty()),style=MaterialTheme.typography.headlineSmall,textAlign=TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(room.title,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
        }
    }
}

@Composable
private fun RoomMemberAvatar(member: com.music.orb.data.rooms.RoomMember?, modifier: Modifier) {
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),contentAlignment=Alignment.Center) {
        if(member?.avatar!=null)AsyncImage(member.avatar,member.name,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
        else Text(member?.name?.take(1)?.uppercase(Locale.ROOT).orEmpty(),style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** A short original chime, without taking audio focus away from room playback. */
private class RoomWelcomeSound(context: Context) {
    private val loaded = kotlinx.coroutines.CompletableDeferred<Boolean>()
    private val pool = android.media.SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
        android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private var released = false
    private val soundId: Int
    init {
        pool.setOnLoadCompleteListener { _, _, status -> loaded.complete(status == 0) }
        soundId = pool.load(context, R.raw.orb_room_welcome, 1)
    }
    suspend fun play() {
        val ready = kotlinx.coroutines.withTimeoutOrNull(600) { loaded.await() } == true
        if(ready && !released) pool.play(soundId,.32f,.32f,1,0,1f)
    }
    fun release() { released=true; pool.release(); loaded.complete(false) }
}

@Composable
private fun RoomJoinAnimation(room: ListeningRoom, onFinished: () -> Unit) {
    val context = LocalContext.current
    val progress = remember(room.id) { Animatable(0f) }
    val tracks = remember(room.id) { room.queue.distinctBy { it.thumbnailUrl ?: it.videoId }.take(10) }
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(room.id) {
        kotlinx.coroutines.coroutineScope {
            val ticks = launch {
                if (tracks.isNotEmpty()) {
                    var previousSector = -1
                    var lastTickMs = 0L
                    snapshotFlow { progress.value }.collect { p ->
                        val sector = kotlin.math.floor((.12f*p + 2.4f*p*p*p)*tracks.size).toInt()
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (sector > previousSector && p >= .05f && p < .87f && now-lastTickMs >= 65L) {
                            com.music.orb.ui.components.OrbHaptics.roomArtworkTick(context)
                            lastTickMs = now
                        }
                        if (p >= .05f) previousSector = sector
                    }
                }
            }
            progress.animateTo(1f, tween(if (tracks.isEmpty()) 2000 else 4400, easing = LinearEasing))
            ticks.cancel()
        }
        finish()
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val areaWidth = maxWidth
        val areaHeight = maxHeight
        val coverSize = minOf(areaWidth*.45f, areaHeight*.25f, 200.dp)
        val p = progress.value
        val opacity = (p/.12f).coerceIn(0f, 1f) * (1f-((p-.87f)/.13f).coerceIn(0f, 1f))
        if (tracks.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer {
                alpha = opacity
                scaleX = .85f + .15f*FastOutSlowInEasing.transform(p)
                scaleY = scaleX
            }) {
                if (room.coverUrl != null) {
                    RoomArtwork(room.coverUrl, Modifier.size(coverSize))
                    Spacer(Modifier.height(16.dp))
                }
                Text(room.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp))
            }
        } else {
            // Cubic angular travel starts slowly and accelerates continuously.
            val travel = 6.283185f*(.12f*p + 2.4f*p*p*p)
            tracks.forEachIndexed { index, song ->
                val angle = index*6.283185f/tracks.size + travel
                val depth = (cos(angle)+1f)/2f
                RoomArtwork(song.thumbnailUrl, Modifier.size(coverSize).zIndex(depth).graphicsLayer {
                    translationY = sin(angle)*(areaHeight*.34f).toPx()
                    translationX = sin(angle*.7f)*(areaWidth*.12f).toPx()
                    val scale = .52f + .48f*depth
                    scaleX = scale; scaleY = scale
                    rotationX = sin(angle)*24f
                    rotationY = sin(angle)*32f
                    rotationZ = sin(angle+.4f)*18f
                    cameraDistance = 36.dp.toPx()
                    alpha = opacity*(.4f+.6f*depth)
                })
                }
            }
        }
    }

@Composable
private fun RoomCreationAnimation(room:ListeningRoom,onFinished:()->Unit) {
    val progress=remember(room.id){Animatable(0f)}
    val tracks=remember(room.id){room.queue.distinctBy{it.thumbnailUrl?:it.videoId}.take(12)}
    LaunchedEffect(room.id) {
        progress.animateTo(1f, tween(if (tracks.isEmpty()) 2200 else 4400))
        delay(500)
        onFinished()
    }
    BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        val size=minOf(maxWidth*.48f,maxHeight*.36f,240.dp)
        val p=progress.value
        if(tracks.isNotEmpty()) tracks.forEachIndexed {i,song->
            val spread=when {p<.24f->FastOutSlowInEasing.transform(p/.24f);p<.56f->1f;else->1f-FastOutSlowInEasing.transform(((p-.56f)/.34f).coerceIn(0f,1f))}
            val angle=i*6.283185f/tracks.size+p*3.8f
            RoomArtwork(song.thumbnailUrl,Modifier.size(size*.68f).graphicsLayer {
                translationX=cos(angle)*size.toPx()*.7f*spread
                translationY=sin(angle)*size.toPx()*.45f*spread
                rotationY=sin(angle)*55f*spread;rotationZ=cos(angle)*8f*spread;cameraDistance=40.dp.toPx()
                scaleX=.8f+.2f*spread;scaleY=scaleX
                alpha=(1f-((p-.78f)/.14f).coerceIn(0f,1f))*((p/.12f).coerceIn(0f,1f))
            })
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.graphicsLayer {
            val reveal=if(tracks.isEmpty())(p/.55f).coerceIn(0f,1f) else ((p-.78f)/.2f).coerceIn(0f,1f)
            alpha=reveal;scaleX=.65f+.35f*FastOutSlowInEasing.transform(reveal);scaleY=scaleX
            rotationY=(1f-reveal)*35f;cameraDistance=40.dp.toPx()
        }) {
            RoomArtwork(room.coverUrl,Modifier.size(size))
            Spacer(Modifier.height(20.dp));Text(room.title,style=MaterialTheme.typography.headlineSmall,textAlign=TextAlign.Center,modifier=Modifier.padding(horizontal=24.dp))
        }
    }
}

@Composable
private fun roomErrorText(reason:String?):String = stringResource(when(reason) {
    "ROOM_CAPACITY"->R.string.rooms_error_capacity
    "ROOM_PREMIUM_CHECK"->R.string.rooms_error_premium_check
    "ROOM_PREMIUM_ONLY"->R.string.rooms_error_premium_only
    "ROOM_DURATION"->R.string.rooms_error_network
    "ROOM_OTHER_DEVICE" -> R.string.rooms_other_device
    "ROOM_PLAYING_ELSEWHERE", "ROOM_NOT_ACTIVE"->R.string.rooms_error_playing_elsewhere
    "ROOM_SIGN_IN"->R.string.rooms_error_signin
    "ROOM_FULL"->R.string.rooms_error_full
    "ROOM_NOT_FOUND"->R.string.rooms_error_code
    "ROOM_CLOSED","ROOM_NOT_MEMBER"->R.string.rooms_error_closed
    "ROOM_HOST_OFFLINE"->R.string.rooms_error_offline
    "ROOM_ALREADY_JOINED"->R.string.rooms_error_active
    "ROOM_RATE_LIMIT"->R.string.rooms_error_rate
    "ROOM_TRACK_UNAVAILABLE"->R.string.rooms_error_track
    "ROOM_COVER"->R.string.rooms_error_cover
    "ROOM_HOST_ONLY"->R.string.rooms_host_controls
    "ROOM_QUEUE_LIMIT"->R.string.rooms_error_limit
    else->R.string.rooms_error_network
})
