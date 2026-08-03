// GENERATED CODE - DO NOT MODIFY BY HAND
// coverage:ignore-file
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'api.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

// dart format off
T _$identity<T>(T value) => value;

/// @nodoc
mixin _$BridgeEvent {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is BridgeEvent);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'BridgeEvent()';
  }
}

/// @nodoc
class $BridgeEventCopyWith<$Res> {
  $BridgeEventCopyWith(BridgeEvent _, $Res Function(BridgeEvent) __);
}

/// Adds pattern-matching-related methods to [BridgeEvent].
extension BridgeEventPatterns on BridgeEvent {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(BridgeEvent_IncomingChitthi value)? incomingChitthi,
    TResult Function(BridgeEvent_IncomingDirectMessage value)?
        incomingDirectMessage,
    TResult Function(BridgeEvent_IncomingMedia value)? incomingMedia,
    TResult Function(BridgeEvent_IncomingCallSignal value)? incomingCallSignal,
    TResult Function(BridgeEvent_IncomingMessageRequest value)?
        incomingMessageRequest,
    TResult Function(BridgeEvent_MessageStatus value)? messageStatus,
    TResult Function(BridgeEvent_PeerProfileUpdated value)? peerProfileUpdated,
    TResult Function(BridgeEvent_ComradePresence value)? comradePresence,
    TResult Function(BridgeEvent_ComradeNudge value)? comradeNudge,
    TResult Function(BridgeEvent_TogetherInvited value)? togetherInvited,
    TResult Function(BridgeEvent_TogetherJoined value)? togetherJoined,
    TResult Function(BridgeEvent_TogetherCommand value)? togetherCommand,
    TResult Function(BridgeEvent_TogetherCorrection value)? togetherCorrection,
    TResult Function(BridgeEvent_TogetherEnded value)? togetherEnded,
    TResult Function(BridgeEvent_MeshStatusChanged value)? meshStatusChanged,
    TResult Function(BridgeEvent_LedgerUpdated value)? ledgerUpdated,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case BridgeEvent_IncomingChitthi() when incomingChitthi != null:
        return incomingChitthi(_that);
      case BridgeEvent_IncomingDirectMessage()
          when incomingDirectMessage != null:
        return incomingDirectMessage(_that);
      case BridgeEvent_IncomingMedia() when incomingMedia != null:
        return incomingMedia(_that);
      case BridgeEvent_IncomingCallSignal() when incomingCallSignal != null:
        return incomingCallSignal(_that);
      case BridgeEvent_IncomingMessageRequest()
          when incomingMessageRequest != null:
        return incomingMessageRequest(_that);
      case BridgeEvent_MessageStatus() when messageStatus != null:
        return messageStatus(_that);
      case BridgeEvent_PeerProfileUpdated() when peerProfileUpdated != null:
        return peerProfileUpdated(_that);
      case BridgeEvent_ComradePresence() when comradePresence != null:
        return comradePresence(_that);
      case BridgeEvent_ComradeNudge() when comradeNudge != null:
        return comradeNudge(_that);
      case BridgeEvent_TogetherInvited() when togetherInvited != null:
        return togetherInvited(_that);
      case BridgeEvent_TogetherJoined() when togetherJoined != null:
        return togetherJoined(_that);
      case BridgeEvent_TogetherCommand() when togetherCommand != null:
        return togetherCommand(_that);
      case BridgeEvent_TogetherCorrection() when togetherCorrection != null:
        return togetherCorrection(_that);
      case BridgeEvent_TogetherEnded() when togetherEnded != null:
        return togetherEnded(_that);
      case BridgeEvent_MeshStatusChanged() when meshStatusChanged != null:
        return meshStatusChanged(_that);
      case BridgeEvent_LedgerUpdated() when ledgerUpdated != null:
        return ledgerUpdated(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(BridgeEvent_IncomingChitthi value)
        incomingChitthi,
    required TResult Function(BridgeEvent_IncomingDirectMessage value)
        incomingDirectMessage,
    required TResult Function(BridgeEvent_IncomingMedia value) incomingMedia,
    required TResult Function(BridgeEvent_IncomingCallSignal value)
        incomingCallSignal,
    required TResult Function(BridgeEvent_IncomingMessageRequest value)
        incomingMessageRequest,
    required TResult Function(BridgeEvent_MessageStatus value) messageStatus,
    required TResult Function(BridgeEvent_PeerProfileUpdated value)
        peerProfileUpdated,
    required TResult Function(BridgeEvent_ComradePresence value)
        comradePresence,
    required TResult Function(BridgeEvent_ComradeNudge value) comradeNudge,
    required TResult Function(BridgeEvent_TogetherInvited value)
        togetherInvited,
    required TResult Function(BridgeEvent_TogetherJoined value) togetherJoined,
    required TResult Function(BridgeEvent_TogetherCommand value)
        togetherCommand,
    required TResult Function(BridgeEvent_TogetherCorrection value)
        togetherCorrection,
    required TResult Function(BridgeEvent_TogetherEnded value) togetherEnded,
    required TResult Function(BridgeEvent_MeshStatusChanged value)
        meshStatusChanged,
    required TResult Function(BridgeEvent_LedgerUpdated value) ledgerUpdated,
  }) {
    final _that = this;
    switch (_that) {
      case BridgeEvent_IncomingChitthi():
        return incomingChitthi(_that);
      case BridgeEvent_IncomingDirectMessage():
        return incomingDirectMessage(_that);
      case BridgeEvent_IncomingMedia():
        return incomingMedia(_that);
      case BridgeEvent_IncomingCallSignal():
        return incomingCallSignal(_that);
      case BridgeEvent_IncomingMessageRequest():
        return incomingMessageRequest(_that);
      case BridgeEvent_MessageStatus():
        return messageStatus(_that);
      case BridgeEvent_PeerProfileUpdated():
        return peerProfileUpdated(_that);
      case BridgeEvent_ComradePresence():
        return comradePresence(_that);
      case BridgeEvent_ComradeNudge():
        return comradeNudge(_that);
      case BridgeEvent_TogetherInvited():
        return togetherInvited(_that);
      case BridgeEvent_TogetherJoined():
        return togetherJoined(_that);
      case BridgeEvent_TogetherCommand():
        return togetherCommand(_that);
      case BridgeEvent_TogetherCorrection():
        return togetherCorrection(_that);
      case BridgeEvent_TogetherEnded():
        return togetherEnded(_that);
      case BridgeEvent_MeshStatusChanged():
        return meshStatusChanged(_that);
      case BridgeEvent_LedgerUpdated():
        return ledgerUpdated(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(BridgeEvent_IncomingChitthi value)? incomingChitthi,
    TResult? Function(BridgeEvent_IncomingDirectMessage value)?
        incomingDirectMessage,
    TResult? Function(BridgeEvent_IncomingMedia value)? incomingMedia,
    TResult? Function(BridgeEvent_IncomingCallSignal value)? incomingCallSignal,
    TResult? Function(BridgeEvent_IncomingMessageRequest value)?
        incomingMessageRequest,
    TResult? Function(BridgeEvent_MessageStatus value)? messageStatus,
    TResult? Function(BridgeEvent_PeerProfileUpdated value)? peerProfileUpdated,
    TResult? Function(BridgeEvent_ComradePresence value)? comradePresence,
    TResult? Function(BridgeEvent_ComradeNudge value)? comradeNudge,
    TResult? Function(BridgeEvent_TogetherInvited value)? togetherInvited,
    TResult? Function(BridgeEvent_TogetherJoined value)? togetherJoined,
    TResult? Function(BridgeEvent_TogetherCommand value)? togetherCommand,
    TResult? Function(BridgeEvent_TogetherCorrection value)? togetherCorrection,
    TResult? Function(BridgeEvent_TogetherEnded value)? togetherEnded,
    TResult? Function(BridgeEvent_MeshStatusChanged value)? meshStatusChanged,
    TResult? Function(BridgeEvent_LedgerUpdated value)? ledgerUpdated,
  }) {
    final _that = this;
    switch (_that) {
      case BridgeEvent_IncomingChitthi() when incomingChitthi != null:
        return incomingChitthi(_that);
      case BridgeEvent_IncomingDirectMessage()
          when incomingDirectMessage != null:
        return incomingDirectMessage(_that);
      case BridgeEvent_IncomingMedia() when incomingMedia != null:
        return incomingMedia(_that);
      case BridgeEvent_IncomingCallSignal() when incomingCallSignal != null:
        return incomingCallSignal(_that);
      case BridgeEvent_IncomingMessageRequest()
          when incomingMessageRequest != null:
        return incomingMessageRequest(_that);
      case BridgeEvent_MessageStatus() when messageStatus != null:
        return messageStatus(_that);
      case BridgeEvent_PeerProfileUpdated() when peerProfileUpdated != null:
        return peerProfileUpdated(_that);
      case BridgeEvent_ComradePresence() when comradePresence != null:
        return comradePresence(_that);
      case BridgeEvent_ComradeNudge() when comradeNudge != null:
        return comradeNudge(_that);
      case BridgeEvent_TogetherInvited() when togetherInvited != null:
        return togetherInvited(_that);
      case BridgeEvent_TogetherJoined() when togetherJoined != null:
        return togetherJoined(_that);
      case BridgeEvent_TogetherCommand() when togetherCommand != null:
        return togetherCommand(_that);
      case BridgeEvent_TogetherCorrection() when togetherCorrection != null:
        return togetherCorrection(_that);
      case BridgeEvent_TogetherEnded() when togetherEnded != null:
        return togetherEnded(_that);
      case BridgeEvent_MeshStatusChanged() when meshStatusChanged != null:
        return meshStatusChanged(_that);
      case BridgeEvent_LedgerUpdated() when ledgerUpdated != null:
        return ledgerUpdated(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(ChitthiDto field0)? incomingChitthi,
    TResult Function(DirectMessageDto field0)? incomingDirectMessage,
    TResult Function(MediaMessageDto field0)? incomingMedia,
    TResult Function(CallSignalDto field0)? incomingCallSignal,
    TResult Function(MessageRequestDto field0)? incomingMessageRequest,
    TResult Function(String peer, List<String> messageIds, String status)?
        messageStatus,
    TResult Function(String peer, String? name)? peerProfileUpdated,
    TResult Function(String peer, String? name, bool online, BigInt at)?
        comradePresence,
    TResult Function(String peer, String? name)? comradeNudge,
    TResult Function(TogetherInviteDto field0)? togetherInvited,
    TResult Function(String sessionId, String peer)? togetherJoined,
    TResult Function(TogetherCommandDto field0)? togetherCommand,
    TResult Function(TogetherCorrectionDto field0)? togetherCorrection,
    TResult Function(String sessionId, String peer, bool byPeer)? togetherEnded,
    TResult Function(MeshStatusDto field0)? meshStatusChanged,
    TResult Function(String ledger)? ledgerUpdated,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case BridgeEvent_IncomingChitthi() when incomingChitthi != null:
        return incomingChitthi(_that.field0);
      case BridgeEvent_IncomingDirectMessage()
          when incomingDirectMessage != null:
        return incomingDirectMessage(_that.field0);
      case BridgeEvent_IncomingMedia() when incomingMedia != null:
        return incomingMedia(_that.field0);
      case BridgeEvent_IncomingCallSignal() when incomingCallSignal != null:
        return incomingCallSignal(_that.field0);
      case BridgeEvent_IncomingMessageRequest()
          when incomingMessageRequest != null:
        return incomingMessageRequest(_that.field0);
      case BridgeEvent_MessageStatus() when messageStatus != null:
        return messageStatus(_that.peer, _that.messageIds, _that.status);
      case BridgeEvent_PeerProfileUpdated() when peerProfileUpdated != null:
        return peerProfileUpdated(_that.peer, _that.name);
      case BridgeEvent_ComradePresence() when comradePresence != null:
        return comradePresence(_that.peer, _that.name, _that.online, _that.at);
      case BridgeEvent_ComradeNudge() when comradeNudge != null:
        return comradeNudge(_that.peer, _that.name);
      case BridgeEvent_TogetherInvited() when togetherInvited != null:
        return togetherInvited(_that.field0);
      case BridgeEvent_TogetherJoined() when togetherJoined != null:
        return togetherJoined(_that.sessionId, _that.peer);
      case BridgeEvent_TogetherCommand() when togetherCommand != null:
        return togetherCommand(_that.field0);
      case BridgeEvent_TogetherCorrection() when togetherCorrection != null:
        return togetherCorrection(_that.field0);
      case BridgeEvent_TogetherEnded() when togetherEnded != null:
        return togetherEnded(_that.sessionId, _that.peer, _that.byPeer);
      case BridgeEvent_MeshStatusChanged() when meshStatusChanged != null:
        return meshStatusChanged(_that.field0);
      case BridgeEvent_LedgerUpdated() when ledgerUpdated != null:
        return ledgerUpdated(_that.ledger);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(ChitthiDto field0) incomingChitthi,
    required TResult Function(DirectMessageDto field0) incomingDirectMessage,
    required TResult Function(MediaMessageDto field0) incomingMedia,
    required TResult Function(CallSignalDto field0) incomingCallSignal,
    required TResult Function(MessageRequestDto field0) incomingMessageRequest,
    required TResult Function(
            String peer, List<String> messageIds, String status)
        messageStatus,
    required TResult Function(String peer, String? name) peerProfileUpdated,
    required TResult Function(String peer, String? name, bool online, BigInt at)
        comradePresence,
    required TResult Function(String peer, String? name) comradeNudge,
    required TResult Function(TogetherInviteDto field0) togetherInvited,
    required TResult Function(String sessionId, String peer) togetherJoined,
    required TResult Function(TogetherCommandDto field0) togetherCommand,
    required TResult Function(TogetherCorrectionDto field0) togetherCorrection,
    required TResult Function(String sessionId, String peer, bool byPeer)
        togetherEnded,
    required TResult Function(MeshStatusDto field0) meshStatusChanged,
    required TResult Function(String ledger) ledgerUpdated,
  }) {
    final _that = this;
    switch (_that) {
      case BridgeEvent_IncomingChitthi():
        return incomingChitthi(_that.field0);
      case BridgeEvent_IncomingDirectMessage():
        return incomingDirectMessage(_that.field0);
      case BridgeEvent_IncomingMedia():
        return incomingMedia(_that.field0);
      case BridgeEvent_IncomingCallSignal():
        return incomingCallSignal(_that.field0);
      case BridgeEvent_IncomingMessageRequest():
        return incomingMessageRequest(_that.field0);
      case BridgeEvent_MessageStatus():
        return messageStatus(_that.peer, _that.messageIds, _that.status);
      case BridgeEvent_PeerProfileUpdated():
        return peerProfileUpdated(_that.peer, _that.name);
      case BridgeEvent_ComradePresence():
        return comradePresence(_that.peer, _that.name, _that.online, _that.at);
      case BridgeEvent_ComradeNudge():
        return comradeNudge(_that.peer, _that.name);
      case BridgeEvent_TogetherInvited():
        return togetherInvited(_that.field0);
      case BridgeEvent_TogetherJoined():
        return togetherJoined(_that.sessionId, _that.peer);
      case BridgeEvent_TogetherCommand():
        return togetherCommand(_that.field0);
      case BridgeEvent_TogetherCorrection():
        return togetherCorrection(_that.field0);
      case BridgeEvent_TogetherEnded():
        return togetherEnded(_that.sessionId, _that.peer, _that.byPeer);
      case BridgeEvent_MeshStatusChanged():
        return meshStatusChanged(_that.field0);
      case BridgeEvent_LedgerUpdated():
        return ledgerUpdated(_that.ledger);
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(ChitthiDto field0)? incomingChitthi,
    TResult? Function(DirectMessageDto field0)? incomingDirectMessage,
    TResult? Function(MediaMessageDto field0)? incomingMedia,
    TResult? Function(CallSignalDto field0)? incomingCallSignal,
    TResult? Function(MessageRequestDto field0)? incomingMessageRequest,
    TResult? Function(String peer, List<String> messageIds, String status)?
        messageStatus,
    TResult? Function(String peer, String? name)? peerProfileUpdated,
    TResult? Function(String peer, String? name, bool online, BigInt at)?
        comradePresence,
    TResult? Function(String peer, String? name)? comradeNudge,
    TResult? Function(TogetherInviteDto field0)? togetherInvited,
    TResult? Function(String sessionId, String peer)? togetherJoined,
    TResult? Function(TogetherCommandDto field0)? togetherCommand,
    TResult? Function(TogetherCorrectionDto field0)? togetherCorrection,
    TResult? Function(String sessionId, String peer, bool byPeer)?
        togetherEnded,
    TResult? Function(MeshStatusDto field0)? meshStatusChanged,
    TResult? Function(String ledger)? ledgerUpdated,
  }) {
    final _that = this;
    switch (_that) {
      case BridgeEvent_IncomingChitthi() when incomingChitthi != null:
        return incomingChitthi(_that.field0);
      case BridgeEvent_IncomingDirectMessage()
          when incomingDirectMessage != null:
        return incomingDirectMessage(_that.field0);
      case BridgeEvent_IncomingMedia() when incomingMedia != null:
        return incomingMedia(_that.field0);
      case BridgeEvent_IncomingCallSignal() when incomingCallSignal != null:
        return incomingCallSignal(_that.field0);
      case BridgeEvent_IncomingMessageRequest()
          when incomingMessageRequest != null:
        return incomingMessageRequest(_that.field0);
      case BridgeEvent_MessageStatus() when messageStatus != null:
        return messageStatus(_that.peer, _that.messageIds, _that.status);
      case BridgeEvent_PeerProfileUpdated() when peerProfileUpdated != null:
        return peerProfileUpdated(_that.peer, _that.name);
      case BridgeEvent_ComradePresence() when comradePresence != null:
        return comradePresence(_that.peer, _that.name, _that.online, _that.at);
      case BridgeEvent_ComradeNudge() when comradeNudge != null:
        return comradeNudge(_that.peer, _that.name);
      case BridgeEvent_TogetherInvited() when togetherInvited != null:
        return togetherInvited(_that.field0);
      case BridgeEvent_TogetherJoined() when togetherJoined != null:
        return togetherJoined(_that.sessionId, _that.peer);
      case BridgeEvent_TogetherCommand() when togetherCommand != null:
        return togetherCommand(_that.field0);
      case BridgeEvent_TogetherCorrection() when togetherCorrection != null:
        return togetherCorrection(_that.field0);
      case BridgeEvent_TogetherEnded() when togetherEnded != null:
        return togetherEnded(_that.sessionId, _that.peer, _that.byPeer);
      case BridgeEvent_MeshStatusChanged() when meshStatusChanged != null:
        return meshStatusChanged(_that.field0);
      case BridgeEvent_LedgerUpdated() when ledgerUpdated != null:
        return ledgerUpdated(_that.ledger);
      case _:
        return null;
    }
  }
}

/// @nodoc

class BridgeEvent_IncomingChitthi extends BridgeEvent {
  const BridgeEvent_IncomingChitthi(this.field0) : super._();

  final ChitthiDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_IncomingChitthiCopyWith<BridgeEvent_IncomingChitthi>
      get copyWith => _$BridgeEvent_IncomingChitthiCopyWithImpl<
          BridgeEvent_IncomingChitthi>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_IncomingChitthi &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.incomingChitthi(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_IncomingChitthiCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_IncomingChitthiCopyWith(
          BridgeEvent_IncomingChitthi value,
          $Res Function(BridgeEvent_IncomingChitthi) _then) =
      _$BridgeEvent_IncomingChitthiCopyWithImpl;
  @useResult
  $Res call({ChitthiDto field0});
}

/// @nodoc
class _$BridgeEvent_IncomingChitthiCopyWithImpl<$Res>
    implements $BridgeEvent_IncomingChitthiCopyWith<$Res> {
  _$BridgeEvent_IncomingChitthiCopyWithImpl(this._self, this._then);

  final BridgeEvent_IncomingChitthi _self;
  final $Res Function(BridgeEvent_IncomingChitthi) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_IncomingChitthi(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as ChitthiDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_IncomingDirectMessage extends BridgeEvent {
  const BridgeEvent_IncomingDirectMessage(this.field0) : super._();

  final DirectMessageDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_IncomingDirectMessageCopyWith<BridgeEvent_IncomingDirectMessage>
      get copyWith => _$BridgeEvent_IncomingDirectMessageCopyWithImpl<
          BridgeEvent_IncomingDirectMessage>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_IncomingDirectMessage &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.incomingDirectMessage(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_IncomingDirectMessageCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_IncomingDirectMessageCopyWith(
          BridgeEvent_IncomingDirectMessage value,
          $Res Function(BridgeEvent_IncomingDirectMessage) _then) =
      _$BridgeEvent_IncomingDirectMessageCopyWithImpl;
  @useResult
  $Res call({DirectMessageDto field0});
}

/// @nodoc
class _$BridgeEvent_IncomingDirectMessageCopyWithImpl<$Res>
    implements $BridgeEvent_IncomingDirectMessageCopyWith<$Res> {
  _$BridgeEvent_IncomingDirectMessageCopyWithImpl(this._self, this._then);

  final BridgeEvent_IncomingDirectMessage _self;
  final $Res Function(BridgeEvent_IncomingDirectMessage) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_IncomingDirectMessage(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as DirectMessageDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_IncomingMedia extends BridgeEvent {
  const BridgeEvent_IncomingMedia(this.field0) : super._();

  final MediaMessageDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_IncomingMediaCopyWith<BridgeEvent_IncomingMedia> get copyWith =>
      _$BridgeEvent_IncomingMediaCopyWithImpl<BridgeEvent_IncomingMedia>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_IncomingMedia &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.incomingMedia(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_IncomingMediaCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_IncomingMediaCopyWith(BridgeEvent_IncomingMedia value,
          $Res Function(BridgeEvent_IncomingMedia) _then) =
      _$BridgeEvent_IncomingMediaCopyWithImpl;
  @useResult
  $Res call({MediaMessageDto field0});
}

/// @nodoc
class _$BridgeEvent_IncomingMediaCopyWithImpl<$Res>
    implements $BridgeEvent_IncomingMediaCopyWith<$Res> {
  _$BridgeEvent_IncomingMediaCopyWithImpl(this._self, this._then);

  final BridgeEvent_IncomingMedia _self;
  final $Res Function(BridgeEvent_IncomingMedia) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_IncomingMedia(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as MediaMessageDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_IncomingCallSignal extends BridgeEvent {
  const BridgeEvent_IncomingCallSignal(this.field0) : super._();

  final CallSignalDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_IncomingCallSignalCopyWith<BridgeEvent_IncomingCallSignal>
      get copyWith => _$BridgeEvent_IncomingCallSignalCopyWithImpl<
          BridgeEvent_IncomingCallSignal>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_IncomingCallSignal &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.incomingCallSignal(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_IncomingCallSignalCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_IncomingCallSignalCopyWith(
          BridgeEvent_IncomingCallSignal value,
          $Res Function(BridgeEvent_IncomingCallSignal) _then) =
      _$BridgeEvent_IncomingCallSignalCopyWithImpl;
  @useResult
  $Res call({CallSignalDto field0});
}

/// @nodoc
class _$BridgeEvent_IncomingCallSignalCopyWithImpl<$Res>
    implements $BridgeEvent_IncomingCallSignalCopyWith<$Res> {
  _$BridgeEvent_IncomingCallSignalCopyWithImpl(this._self, this._then);

  final BridgeEvent_IncomingCallSignal _self;
  final $Res Function(BridgeEvent_IncomingCallSignal) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_IncomingCallSignal(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as CallSignalDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_IncomingMessageRequest extends BridgeEvent {
  const BridgeEvent_IncomingMessageRequest(this.field0) : super._();

  final MessageRequestDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_IncomingMessageRequestCopyWith<
          BridgeEvent_IncomingMessageRequest>
      get copyWith => _$BridgeEvent_IncomingMessageRequestCopyWithImpl<
          BridgeEvent_IncomingMessageRequest>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_IncomingMessageRequest &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.incomingMessageRequest(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_IncomingMessageRequestCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_IncomingMessageRequestCopyWith(
          BridgeEvent_IncomingMessageRequest value,
          $Res Function(BridgeEvent_IncomingMessageRequest) _then) =
      _$BridgeEvent_IncomingMessageRequestCopyWithImpl;
  @useResult
  $Res call({MessageRequestDto field0});
}

/// @nodoc
class _$BridgeEvent_IncomingMessageRequestCopyWithImpl<$Res>
    implements $BridgeEvent_IncomingMessageRequestCopyWith<$Res> {
  _$BridgeEvent_IncomingMessageRequestCopyWithImpl(this._self, this._then);

  final BridgeEvent_IncomingMessageRequest _self;
  final $Res Function(BridgeEvent_IncomingMessageRequest) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_IncomingMessageRequest(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as MessageRequestDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_MessageStatus extends BridgeEvent {
  const BridgeEvent_MessageStatus(
      {required this.peer,
      required final List<String> messageIds,
      required this.status})
      : _messageIds = messageIds,
        super._();

  final String peer;
  final List<String> _messageIds;
  List<String> get messageIds {
    if (_messageIds is EqualUnmodifiableListView) return _messageIds;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_messageIds);
  }

  final String status;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_MessageStatusCopyWith<BridgeEvent_MessageStatus> get copyWith =>
      _$BridgeEvent_MessageStatusCopyWithImpl<BridgeEvent_MessageStatus>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_MessageStatus &&
            (identical(other.peer, peer) || other.peer == peer) &&
            const DeepCollectionEquality()
                .equals(other._messageIds, _messageIds) &&
            (identical(other.status, status) || other.status == status));
  }

  @override
  int get hashCode => Object.hash(runtimeType, peer,
      const DeepCollectionEquality().hash(_messageIds), status);

  @override
  String toString() {
    return 'BridgeEvent.messageStatus(peer: $peer, messageIds: $messageIds, status: $status)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_MessageStatusCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_MessageStatusCopyWith(BridgeEvent_MessageStatus value,
          $Res Function(BridgeEvent_MessageStatus) _then) =
      _$BridgeEvent_MessageStatusCopyWithImpl;
  @useResult
  $Res call({String peer, List<String> messageIds, String status});
}

/// @nodoc
class _$BridgeEvent_MessageStatusCopyWithImpl<$Res>
    implements $BridgeEvent_MessageStatusCopyWith<$Res> {
  _$BridgeEvent_MessageStatusCopyWithImpl(this._self, this._then);

  final BridgeEvent_MessageStatus _self;
  final $Res Function(BridgeEvent_MessageStatus) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? peer = null,
    Object? messageIds = null,
    Object? status = null,
  }) {
    return _then(BridgeEvent_MessageStatus(
      peer: null == peer
          ? _self.peer
          : peer // ignore: cast_nullable_to_non_nullable
              as String,
      messageIds: null == messageIds
          ? _self._messageIds
          : messageIds // ignore: cast_nullable_to_non_nullable
              as List<String>,
      status: null == status
          ? _self.status
          : status // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class BridgeEvent_PeerProfileUpdated extends BridgeEvent {
  const BridgeEvent_PeerProfileUpdated({required this.peer, this.name})
      : super._();

  final String peer;
  final String? name;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_PeerProfileUpdatedCopyWith<BridgeEvent_PeerProfileUpdated>
      get copyWith => _$BridgeEvent_PeerProfileUpdatedCopyWithImpl<
          BridgeEvent_PeerProfileUpdated>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_PeerProfileUpdated &&
            (identical(other.peer, peer) || other.peer == peer) &&
            (identical(other.name, name) || other.name == name));
  }

  @override
  int get hashCode => Object.hash(runtimeType, peer, name);

  @override
  String toString() {
    return 'BridgeEvent.peerProfileUpdated(peer: $peer, name: $name)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_PeerProfileUpdatedCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_PeerProfileUpdatedCopyWith(
          BridgeEvent_PeerProfileUpdated value,
          $Res Function(BridgeEvent_PeerProfileUpdated) _then) =
      _$BridgeEvent_PeerProfileUpdatedCopyWithImpl;
  @useResult
  $Res call({String peer, String? name});
}

/// @nodoc
class _$BridgeEvent_PeerProfileUpdatedCopyWithImpl<$Res>
    implements $BridgeEvent_PeerProfileUpdatedCopyWith<$Res> {
  _$BridgeEvent_PeerProfileUpdatedCopyWithImpl(this._self, this._then);

  final BridgeEvent_PeerProfileUpdated _self;
  final $Res Function(BridgeEvent_PeerProfileUpdated) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? peer = null,
    Object? name = freezed,
  }) {
    return _then(BridgeEvent_PeerProfileUpdated(
      peer: null == peer
          ? _self.peer
          : peer // ignore: cast_nullable_to_non_nullable
              as String,
      name: freezed == name
          ? _self.name
          : name // ignore: cast_nullable_to_non_nullable
              as String?,
    ));
  }
}

/// @nodoc

class BridgeEvent_ComradePresence extends BridgeEvent {
  const BridgeEvent_ComradePresence(
      {required this.peer, this.name, required this.online, required this.at})
      : super._();

  final String peer;
  final String? name;
  final bool online;
  final BigInt at;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_ComradePresenceCopyWith<BridgeEvent_ComradePresence>
      get copyWith => _$BridgeEvent_ComradePresenceCopyWithImpl<
          BridgeEvent_ComradePresence>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_ComradePresence &&
            (identical(other.peer, peer) || other.peer == peer) &&
            (identical(other.name, name) || other.name == name) &&
            (identical(other.online, online) || other.online == online) &&
            (identical(other.at, at) || other.at == at));
  }

  @override
  int get hashCode => Object.hash(runtimeType, peer, name, online, at);

  @override
  String toString() {
    return 'BridgeEvent.comradePresence(peer: $peer, name: $name, online: $online, at: $at)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_ComradePresenceCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_ComradePresenceCopyWith(
          BridgeEvent_ComradePresence value,
          $Res Function(BridgeEvent_ComradePresence) _then) =
      _$BridgeEvent_ComradePresenceCopyWithImpl;
  @useResult
  $Res call({String peer, String? name, bool online, BigInt at});
}

/// @nodoc
class _$BridgeEvent_ComradePresenceCopyWithImpl<$Res>
    implements $BridgeEvent_ComradePresenceCopyWith<$Res> {
  _$BridgeEvent_ComradePresenceCopyWithImpl(this._self, this._then);

  final BridgeEvent_ComradePresence _self;
  final $Res Function(BridgeEvent_ComradePresence) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? peer = null,
    Object? name = freezed,
    Object? online = null,
    Object? at = null,
  }) {
    return _then(BridgeEvent_ComradePresence(
      peer: null == peer
          ? _self.peer
          : peer // ignore: cast_nullable_to_non_nullable
              as String,
      name: freezed == name
          ? _self.name
          : name // ignore: cast_nullable_to_non_nullable
              as String?,
      online: null == online
          ? _self.online
          : online // ignore: cast_nullable_to_non_nullable
              as bool,
      at: null == at
          ? _self.at
          : at // ignore: cast_nullable_to_non_nullable
              as BigInt,
    ));
  }
}

/// @nodoc

class BridgeEvent_ComradeNudge extends BridgeEvent {
  const BridgeEvent_ComradeNudge({required this.peer, this.name}) : super._();

  final String peer;
  final String? name;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_ComradeNudgeCopyWith<BridgeEvent_ComradeNudge> get copyWith =>
      _$BridgeEvent_ComradeNudgeCopyWithImpl<BridgeEvent_ComradeNudge>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_ComradeNudge &&
            (identical(other.peer, peer) || other.peer == peer) &&
            (identical(other.name, name) || other.name == name));
  }

  @override
  int get hashCode => Object.hash(runtimeType, peer, name);

  @override
  String toString() {
    return 'BridgeEvent.comradeNudge(peer: $peer, name: $name)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_ComradeNudgeCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_ComradeNudgeCopyWith(BridgeEvent_ComradeNudge value,
          $Res Function(BridgeEvent_ComradeNudge) _then) =
      _$BridgeEvent_ComradeNudgeCopyWithImpl;
  @useResult
  $Res call({String peer, String? name});
}

/// @nodoc
class _$BridgeEvent_ComradeNudgeCopyWithImpl<$Res>
    implements $BridgeEvent_ComradeNudgeCopyWith<$Res> {
  _$BridgeEvent_ComradeNudgeCopyWithImpl(this._self, this._then);

  final BridgeEvent_ComradeNudge _self;
  final $Res Function(BridgeEvent_ComradeNudge) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? peer = null,
    Object? name = freezed,
  }) {
    return _then(BridgeEvent_ComradeNudge(
      peer: null == peer
          ? _self.peer
          : peer // ignore: cast_nullable_to_non_nullable
              as String,
      name: freezed == name
          ? _self.name
          : name // ignore: cast_nullable_to_non_nullable
              as String?,
    ));
  }
}

/// @nodoc

class BridgeEvent_TogetherInvited extends BridgeEvent {
  const BridgeEvent_TogetherInvited(this.field0) : super._();

  final TogetherInviteDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_TogetherInvitedCopyWith<BridgeEvent_TogetherInvited>
      get copyWith => _$BridgeEvent_TogetherInvitedCopyWithImpl<
          BridgeEvent_TogetherInvited>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_TogetherInvited &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.togetherInvited(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_TogetherInvitedCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_TogetherInvitedCopyWith(
          BridgeEvent_TogetherInvited value,
          $Res Function(BridgeEvent_TogetherInvited) _then) =
      _$BridgeEvent_TogetherInvitedCopyWithImpl;
  @useResult
  $Res call({TogetherInviteDto field0});
}

/// @nodoc
class _$BridgeEvent_TogetherInvitedCopyWithImpl<$Res>
    implements $BridgeEvent_TogetherInvitedCopyWith<$Res> {
  _$BridgeEvent_TogetherInvitedCopyWithImpl(this._self, this._then);

  final BridgeEvent_TogetherInvited _self;
  final $Res Function(BridgeEvent_TogetherInvited) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_TogetherInvited(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as TogetherInviteDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_TogetherJoined extends BridgeEvent {
  const BridgeEvent_TogetherJoined(
      {required this.sessionId, required this.peer})
      : super._();

  final String sessionId;
  final String peer;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_TogetherJoinedCopyWith<BridgeEvent_TogetherJoined>
      get copyWith =>
          _$BridgeEvent_TogetherJoinedCopyWithImpl<BridgeEvent_TogetherJoined>(
              this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_TogetherJoined &&
            (identical(other.sessionId, sessionId) ||
                other.sessionId == sessionId) &&
            (identical(other.peer, peer) || other.peer == peer));
  }

  @override
  int get hashCode => Object.hash(runtimeType, sessionId, peer);

  @override
  String toString() {
    return 'BridgeEvent.togetherJoined(sessionId: $sessionId, peer: $peer)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_TogetherJoinedCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_TogetherJoinedCopyWith(BridgeEvent_TogetherJoined value,
          $Res Function(BridgeEvent_TogetherJoined) _then) =
      _$BridgeEvent_TogetherJoinedCopyWithImpl;
  @useResult
  $Res call({String sessionId, String peer});
}

/// @nodoc
class _$BridgeEvent_TogetherJoinedCopyWithImpl<$Res>
    implements $BridgeEvent_TogetherJoinedCopyWith<$Res> {
  _$BridgeEvent_TogetherJoinedCopyWithImpl(this._self, this._then);

  final BridgeEvent_TogetherJoined _self;
  final $Res Function(BridgeEvent_TogetherJoined) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? sessionId = null,
    Object? peer = null,
  }) {
    return _then(BridgeEvent_TogetherJoined(
      sessionId: null == sessionId
          ? _self.sessionId
          : sessionId // ignore: cast_nullable_to_non_nullable
              as String,
      peer: null == peer
          ? _self.peer
          : peer // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class BridgeEvent_TogetherCommand extends BridgeEvent {
  const BridgeEvent_TogetherCommand(this.field0) : super._();

  final TogetherCommandDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_TogetherCommandCopyWith<BridgeEvent_TogetherCommand>
      get copyWith => _$BridgeEvent_TogetherCommandCopyWithImpl<
          BridgeEvent_TogetherCommand>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_TogetherCommand &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.togetherCommand(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_TogetherCommandCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_TogetherCommandCopyWith(
          BridgeEvent_TogetherCommand value,
          $Res Function(BridgeEvent_TogetherCommand) _then) =
      _$BridgeEvent_TogetherCommandCopyWithImpl;
  @useResult
  $Res call({TogetherCommandDto field0});
}

/// @nodoc
class _$BridgeEvent_TogetherCommandCopyWithImpl<$Res>
    implements $BridgeEvent_TogetherCommandCopyWith<$Res> {
  _$BridgeEvent_TogetherCommandCopyWithImpl(this._self, this._then);

  final BridgeEvent_TogetherCommand _self;
  final $Res Function(BridgeEvent_TogetherCommand) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_TogetherCommand(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as TogetherCommandDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_TogetherCorrection extends BridgeEvent {
  const BridgeEvent_TogetherCorrection(this.field0) : super._();

  final TogetherCorrectionDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_TogetherCorrectionCopyWith<BridgeEvent_TogetherCorrection>
      get copyWith => _$BridgeEvent_TogetherCorrectionCopyWithImpl<
          BridgeEvent_TogetherCorrection>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_TogetherCorrection &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.togetherCorrection(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_TogetherCorrectionCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_TogetherCorrectionCopyWith(
          BridgeEvent_TogetherCorrection value,
          $Res Function(BridgeEvent_TogetherCorrection) _then) =
      _$BridgeEvent_TogetherCorrectionCopyWithImpl;
  @useResult
  $Res call({TogetherCorrectionDto field0});
}

/// @nodoc
class _$BridgeEvent_TogetherCorrectionCopyWithImpl<$Res>
    implements $BridgeEvent_TogetherCorrectionCopyWith<$Res> {
  _$BridgeEvent_TogetherCorrectionCopyWithImpl(this._self, this._then);

  final BridgeEvent_TogetherCorrection _self;
  final $Res Function(BridgeEvent_TogetherCorrection) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_TogetherCorrection(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as TogetherCorrectionDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_TogetherEnded extends BridgeEvent {
  const BridgeEvent_TogetherEnded(
      {required this.sessionId, required this.peer, required this.byPeer})
      : super._();

  final String sessionId;
  final String peer;
  final bool byPeer;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_TogetherEndedCopyWith<BridgeEvent_TogetherEnded> get copyWith =>
      _$BridgeEvent_TogetherEndedCopyWithImpl<BridgeEvent_TogetherEnded>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_TogetherEnded &&
            (identical(other.sessionId, sessionId) ||
                other.sessionId == sessionId) &&
            (identical(other.peer, peer) || other.peer == peer) &&
            (identical(other.byPeer, byPeer) || other.byPeer == byPeer));
  }

  @override
  int get hashCode => Object.hash(runtimeType, sessionId, peer, byPeer);

  @override
  String toString() {
    return 'BridgeEvent.togetherEnded(sessionId: $sessionId, peer: $peer, byPeer: $byPeer)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_TogetherEndedCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_TogetherEndedCopyWith(BridgeEvent_TogetherEnded value,
          $Res Function(BridgeEvent_TogetherEnded) _then) =
      _$BridgeEvent_TogetherEndedCopyWithImpl;
  @useResult
  $Res call({String sessionId, String peer, bool byPeer});
}

/// @nodoc
class _$BridgeEvent_TogetherEndedCopyWithImpl<$Res>
    implements $BridgeEvent_TogetherEndedCopyWith<$Res> {
  _$BridgeEvent_TogetherEndedCopyWithImpl(this._self, this._then);

  final BridgeEvent_TogetherEnded _self;
  final $Res Function(BridgeEvent_TogetherEnded) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? sessionId = null,
    Object? peer = null,
    Object? byPeer = null,
  }) {
    return _then(BridgeEvent_TogetherEnded(
      sessionId: null == sessionId
          ? _self.sessionId
          : sessionId // ignore: cast_nullable_to_non_nullable
              as String,
      peer: null == peer
          ? _self.peer
          : peer // ignore: cast_nullable_to_non_nullable
              as String,
      byPeer: null == byPeer
          ? _self.byPeer
          : byPeer // ignore: cast_nullable_to_non_nullable
              as bool,
    ));
  }
}

/// @nodoc

class BridgeEvent_MeshStatusChanged extends BridgeEvent {
  const BridgeEvent_MeshStatusChanged(this.field0) : super._();

  final MeshStatusDto field0;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_MeshStatusChangedCopyWith<BridgeEvent_MeshStatusChanged>
      get copyWith => _$BridgeEvent_MeshStatusChangedCopyWithImpl<
          BridgeEvent_MeshStatusChanged>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_MeshStatusChanged &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'BridgeEvent.meshStatusChanged(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_MeshStatusChangedCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_MeshStatusChangedCopyWith(
          BridgeEvent_MeshStatusChanged value,
          $Res Function(BridgeEvent_MeshStatusChanged) _then) =
      _$BridgeEvent_MeshStatusChangedCopyWithImpl;
  @useResult
  $Res call({MeshStatusDto field0});
}

/// @nodoc
class _$BridgeEvent_MeshStatusChangedCopyWithImpl<$Res>
    implements $BridgeEvent_MeshStatusChangedCopyWith<$Res> {
  _$BridgeEvent_MeshStatusChangedCopyWithImpl(this._self, this._then);

  final BridgeEvent_MeshStatusChanged _self;
  final $Res Function(BridgeEvent_MeshStatusChanged) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(BridgeEvent_MeshStatusChanged(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as MeshStatusDto,
    ));
  }
}

/// @nodoc

class BridgeEvent_LedgerUpdated extends BridgeEvent {
  const BridgeEvent_LedgerUpdated({required this.ledger}) : super._();

  final String ledger;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $BridgeEvent_LedgerUpdatedCopyWith<BridgeEvent_LedgerUpdated> get copyWith =>
      _$BridgeEvent_LedgerUpdatedCopyWithImpl<BridgeEvent_LedgerUpdated>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is BridgeEvent_LedgerUpdated &&
            (identical(other.ledger, ledger) || other.ledger == ledger));
  }

  @override
  int get hashCode => Object.hash(runtimeType, ledger);

  @override
  String toString() {
    return 'BridgeEvent.ledgerUpdated(ledger: $ledger)';
  }
}

/// @nodoc
abstract mixin class $BridgeEvent_LedgerUpdatedCopyWith<$Res>
    implements $BridgeEventCopyWith<$Res> {
  factory $BridgeEvent_LedgerUpdatedCopyWith(BridgeEvent_LedgerUpdated value,
          $Res Function(BridgeEvent_LedgerUpdated) _then) =
      _$BridgeEvent_LedgerUpdatedCopyWithImpl;
  @useResult
  $Res call({String ledger});
}

/// @nodoc
class _$BridgeEvent_LedgerUpdatedCopyWithImpl<$Res>
    implements $BridgeEvent_LedgerUpdatedCopyWith<$Res> {
  _$BridgeEvent_LedgerUpdatedCopyWithImpl(this._self, this._then);

  final BridgeEvent_LedgerUpdated _self;
  final $Res Function(BridgeEvent_LedgerUpdated) _then;

  /// Create a copy of BridgeEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? ledger = null,
  }) {
    return _then(BridgeEvent_LedgerUpdated(
      ledger: null == ledger
          ? _self.ledger
          : ledger // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc
mixin _$CallSignal {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is CallSignal);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'CallSignal()';
  }
}

/// @nodoc
class $CallSignalCopyWith<$Res> {
  $CallSignalCopyWith(CallSignal _, $Res Function(CallSignal) __);
}

/// Adds pattern-matching-related methods to [CallSignal].
extension CallSignalPatterns on CallSignal {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(CallSignal_Offer value)? offer,
    TResult Function(CallSignal_Answer value)? answer,
    TResult Function(CallSignal_Ice value)? ice,
    TResult Function(CallSignal_Ringing value)? ringing,
    TResult Function(CallSignal_Busy value)? busy,
    TResult Function(CallSignal_Hangup value)? hangup,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case CallSignal_Offer() when offer != null:
        return offer(_that);
      case CallSignal_Answer() when answer != null:
        return answer(_that);
      case CallSignal_Ice() when ice != null:
        return ice(_that);
      case CallSignal_Ringing() when ringing != null:
        return ringing(_that);
      case CallSignal_Busy() when busy != null:
        return busy(_that);
      case CallSignal_Hangup() when hangup != null:
        return hangup(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(CallSignal_Offer value) offer,
    required TResult Function(CallSignal_Answer value) answer,
    required TResult Function(CallSignal_Ice value) ice,
    required TResult Function(CallSignal_Ringing value) ringing,
    required TResult Function(CallSignal_Busy value) busy,
    required TResult Function(CallSignal_Hangup value) hangup,
  }) {
    final _that = this;
    switch (_that) {
      case CallSignal_Offer():
        return offer(_that);
      case CallSignal_Answer():
        return answer(_that);
      case CallSignal_Ice():
        return ice(_that);
      case CallSignal_Ringing():
        return ringing(_that);
      case CallSignal_Busy():
        return busy(_that);
      case CallSignal_Hangup():
        return hangup(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(CallSignal_Offer value)? offer,
    TResult? Function(CallSignal_Answer value)? answer,
    TResult? Function(CallSignal_Ice value)? ice,
    TResult? Function(CallSignal_Ringing value)? ringing,
    TResult? Function(CallSignal_Busy value)? busy,
    TResult? Function(CallSignal_Hangup value)? hangup,
  }) {
    final _that = this;
    switch (_that) {
      case CallSignal_Offer() when offer != null:
        return offer(_that);
      case CallSignal_Answer() when answer != null:
        return answer(_that);
      case CallSignal_Ice() when ice != null:
        return ice(_that);
      case CallSignal_Ringing() when ringing != null:
        return ringing(_that);
      case CallSignal_Busy() when busy != null:
        return busy(_that);
      case CallSignal_Hangup() when hangup != null:
        return hangup(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(String sdp)? offer,
    TResult Function(String sdp)? answer,
    TResult Function(String candidate, String? sdpMid, int? sdpMLineIndex)? ice,
    TResult Function()? ringing,
    TResult Function()? busy,
    TResult Function(HangupReason reason)? hangup,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case CallSignal_Offer() when offer != null:
        return offer(_that.sdp);
      case CallSignal_Answer() when answer != null:
        return answer(_that.sdp);
      case CallSignal_Ice() when ice != null:
        return ice(_that.candidate, _that.sdpMid, _that.sdpMLineIndex);
      case CallSignal_Ringing() when ringing != null:
        return ringing();
      case CallSignal_Busy() when busy != null:
        return busy();
      case CallSignal_Hangup() when hangup != null:
        return hangup(_that.reason);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(String sdp) offer,
    required TResult Function(String sdp) answer,
    required TResult Function(
            String candidate, String? sdpMid, int? sdpMLineIndex)
        ice,
    required TResult Function() ringing,
    required TResult Function() busy,
    required TResult Function(HangupReason reason) hangup,
  }) {
    final _that = this;
    switch (_that) {
      case CallSignal_Offer():
        return offer(_that.sdp);
      case CallSignal_Answer():
        return answer(_that.sdp);
      case CallSignal_Ice():
        return ice(_that.candidate, _that.sdpMid, _that.sdpMLineIndex);
      case CallSignal_Ringing():
        return ringing();
      case CallSignal_Busy():
        return busy();
      case CallSignal_Hangup():
        return hangup(_that.reason);
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(String sdp)? offer,
    TResult? Function(String sdp)? answer,
    TResult? Function(String candidate, String? sdpMid, int? sdpMLineIndex)?
        ice,
    TResult? Function()? ringing,
    TResult? Function()? busy,
    TResult? Function(HangupReason reason)? hangup,
  }) {
    final _that = this;
    switch (_that) {
      case CallSignal_Offer() when offer != null:
        return offer(_that.sdp);
      case CallSignal_Answer() when answer != null:
        return answer(_that.sdp);
      case CallSignal_Ice() when ice != null:
        return ice(_that.candidate, _that.sdpMid, _that.sdpMLineIndex);
      case CallSignal_Ringing() when ringing != null:
        return ringing();
      case CallSignal_Busy() when busy != null:
        return busy();
      case CallSignal_Hangup() when hangup != null:
        return hangup(_that.reason);
      case _:
        return null;
    }
  }
}

/// @nodoc

class CallSignal_Offer extends CallSignal {
  const CallSignal_Offer({required this.sdp}) : super._();

  final String sdp;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $CallSignal_OfferCopyWith<CallSignal_Offer> get copyWith =>
      _$CallSignal_OfferCopyWithImpl<CallSignal_Offer>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is CallSignal_Offer &&
            (identical(other.sdp, sdp) || other.sdp == sdp));
  }

  @override
  int get hashCode => Object.hash(runtimeType, sdp);

  @override
  String toString() {
    return 'CallSignal.offer(sdp: $sdp)';
  }
}

/// @nodoc
abstract mixin class $CallSignal_OfferCopyWith<$Res>
    implements $CallSignalCopyWith<$Res> {
  factory $CallSignal_OfferCopyWith(
          CallSignal_Offer value, $Res Function(CallSignal_Offer) _then) =
      _$CallSignal_OfferCopyWithImpl;
  @useResult
  $Res call({String sdp});
}

/// @nodoc
class _$CallSignal_OfferCopyWithImpl<$Res>
    implements $CallSignal_OfferCopyWith<$Res> {
  _$CallSignal_OfferCopyWithImpl(this._self, this._then);

  final CallSignal_Offer _self;
  final $Res Function(CallSignal_Offer) _then;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? sdp = null,
  }) {
    return _then(CallSignal_Offer(
      sdp: null == sdp
          ? _self.sdp
          : sdp // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class CallSignal_Answer extends CallSignal {
  const CallSignal_Answer({required this.sdp}) : super._();

  final String sdp;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $CallSignal_AnswerCopyWith<CallSignal_Answer> get copyWith =>
      _$CallSignal_AnswerCopyWithImpl<CallSignal_Answer>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is CallSignal_Answer &&
            (identical(other.sdp, sdp) || other.sdp == sdp));
  }

  @override
  int get hashCode => Object.hash(runtimeType, sdp);

  @override
  String toString() {
    return 'CallSignal.answer(sdp: $sdp)';
  }
}

/// @nodoc
abstract mixin class $CallSignal_AnswerCopyWith<$Res>
    implements $CallSignalCopyWith<$Res> {
  factory $CallSignal_AnswerCopyWith(
          CallSignal_Answer value, $Res Function(CallSignal_Answer) _then) =
      _$CallSignal_AnswerCopyWithImpl;
  @useResult
  $Res call({String sdp});
}

/// @nodoc
class _$CallSignal_AnswerCopyWithImpl<$Res>
    implements $CallSignal_AnswerCopyWith<$Res> {
  _$CallSignal_AnswerCopyWithImpl(this._self, this._then);

  final CallSignal_Answer _self;
  final $Res Function(CallSignal_Answer) _then;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? sdp = null,
  }) {
    return _then(CallSignal_Answer(
      sdp: null == sdp
          ? _self.sdp
          : sdp // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class CallSignal_Ice extends CallSignal {
  const CallSignal_Ice(
      {required this.candidate, this.sdpMid, this.sdpMLineIndex})
      : super._();

  final String candidate;
  final String? sdpMid;
  final int? sdpMLineIndex;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $CallSignal_IceCopyWith<CallSignal_Ice> get copyWith =>
      _$CallSignal_IceCopyWithImpl<CallSignal_Ice>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is CallSignal_Ice &&
            (identical(other.candidate, candidate) ||
                other.candidate == candidate) &&
            (identical(other.sdpMid, sdpMid) || other.sdpMid == sdpMid) &&
            (identical(other.sdpMLineIndex, sdpMLineIndex) ||
                other.sdpMLineIndex == sdpMLineIndex));
  }

  @override
  int get hashCode =>
      Object.hash(runtimeType, candidate, sdpMid, sdpMLineIndex);

  @override
  String toString() {
    return 'CallSignal.ice(candidate: $candidate, sdpMid: $sdpMid, sdpMLineIndex: $sdpMLineIndex)';
  }
}

/// @nodoc
abstract mixin class $CallSignal_IceCopyWith<$Res>
    implements $CallSignalCopyWith<$Res> {
  factory $CallSignal_IceCopyWith(
          CallSignal_Ice value, $Res Function(CallSignal_Ice) _then) =
      _$CallSignal_IceCopyWithImpl;
  @useResult
  $Res call({String candidate, String? sdpMid, int? sdpMLineIndex});
}

/// @nodoc
class _$CallSignal_IceCopyWithImpl<$Res>
    implements $CallSignal_IceCopyWith<$Res> {
  _$CallSignal_IceCopyWithImpl(this._self, this._then);

  final CallSignal_Ice _self;
  final $Res Function(CallSignal_Ice) _then;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? candidate = null,
    Object? sdpMid = freezed,
    Object? sdpMLineIndex = freezed,
  }) {
    return _then(CallSignal_Ice(
      candidate: null == candidate
          ? _self.candidate
          : candidate // ignore: cast_nullable_to_non_nullable
              as String,
      sdpMid: freezed == sdpMid
          ? _self.sdpMid
          : sdpMid // ignore: cast_nullable_to_non_nullable
              as String?,
      sdpMLineIndex: freezed == sdpMLineIndex
          ? _self.sdpMLineIndex
          : sdpMLineIndex // ignore: cast_nullable_to_non_nullable
              as int?,
    ));
  }
}

/// @nodoc

class CallSignal_Ringing extends CallSignal {
  const CallSignal_Ringing() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is CallSignal_Ringing);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'CallSignal.ringing()';
  }
}

/// @nodoc

class CallSignal_Busy extends CallSignal {
  const CallSignal_Busy() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is CallSignal_Busy);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'CallSignal.busy()';
  }
}

/// @nodoc

class CallSignal_Hangup extends CallSignal {
  const CallSignal_Hangup({required this.reason}) : super._();

  final HangupReason reason;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $CallSignal_HangupCopyWith<CallSignal_Hangup> get copyWith =>
      _$CallSignal_HangupCopyWithImpl<CallSignal_Hangup>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is CallSignal_Hangup &&
            (identical(other.reason, reason) || other.reason == reason));
  }

  @override
  int get hashCode => Object.hash(runtimeType, reason);

  @override
  String toString() {
    return 'CallSignal.hangup(reason: $reason)';
  }
}

/// @nodoc
abstract mixin class $CallSignal_HangupCopyWith<$Res>
    implements $CallSignalCopyWith<$Res> {
  factory $CallSignal_HangupCopyWith(
          CallSignal_Hangup value, $Res Function(CallSignal_Hangup) _then) =
      _$CallSignal_HangupCopyWithImpl;
  @useResult
  $Res call({HangupReason reason});
}

/// @nodoc
class _$CallSignal_HangupCopyWithImpl<$Res>
    implements $CallSignal_HangupCopyWith<$Res> {
  _$CallSignal_HangupCopyWithImpl(this._self, this._then);

  final CallSignal_Hangup _self;
  final $Res Function(CallSignal_Hangup) _then;

  /// Create a copy of CallSignal
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? reason = null,
  }) {
    return _then(CallSignal_Hangup(
      reason: null == reason
          ? _self.reason
          : reason // ignore: cast_nullable_to_non_nullable
              as HangupReason,
    ));
  }
}

/// @nodoc
mixin _$SyncVerdict {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is SyncVerdict);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'SyncVerdict()';
  }
}

/// @nodoc
class $SyncVerdictCopyWith<$Res> {
  $SyncVerdictCopyWith(SyncVerdict _, $Res Function(SyncVerdict) __);
}

/// Adds pattern-matching-related methods to [SyncVerdict].
extension SyncVerdictPatterns on SyncVerdict {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(SyncVerdict_Hold value)? hold,
    TResult Function(SyncVerdict_Adopt value)? adopt,
    TResult Function(SyncVerdict_Nudge value)? nudge,
    TResult Function(SyncVerdict_Seek value)? seek,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case SyncVerdict_Hold() when hold != null:
        return hold(_that);
      case SyncVerdict_Adopt() when adopt != null:
        return adopt(_that);
      case SyncVerdict_Nudge() when nudge != null:
        return nudge(_that);
      case SyncVerdict_Seek() when seek != null:
        return seek(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(SyncVerdict_Hold value) hold,
    required TResult Function(SyncVerdict_Adopt value) adopt,
    required TResult Function(SyncVerdict_Nudge value) nudge,
    required TResult Function(SyncVerdict_Seek value) seek,
  }) {
    final _that = this;
    switch (_that) {
      case SyncVerdict_Hold():
        return hold(_that);
      case SyncVerdict_Adopt():
        return adopt(_that);
      case SyncVerdict_Nudge():
        return nudge(_that);
      case SyncVerdict_Seek():
        return seek(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(SyncVerdict_Hold value)? hold,
    TResult? Function(SyncVerdict_Adopt value)? adopt,
    TResult? Function(SyncVerdict_Nudge value)? nudge,
    TResult? Function(SyncVerdict_Seek value)? seek,
  }) {
    final _that = this;
    switch (_that) {
      case SyncVerdict_Hold() when hold != null:
        return hold(_that);
      case SyncVerdict_Adopt() when adopt != null:
        return adopt(_that);
      case SyncVerdict_Nudge() when nudge != null:
        return nudge(_that);
      case SyncVerdict_Seek() when seek != null:
        return seek(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function()? hold,
    TResult Function(BigInt posMs, bool playing, BigInt seq)? adopt,
    TResult Function(double rate)? nudge,
    TResult Function(BigInt posMs)? seek,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case SyncVerdict_Hold() when hold != null:
        return hold();
      case SyncVerdict_Adopt() when adopt != null:
        return adopt(_that.posMs, _that.playing, _that.seq);
      case SyncVerdict_Nudge() when nudge != null:
        return nudge(_that.rate);
      case SyncVerdict_Seek() when seek != null:
        return seek(_that.posMs);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function() hold,
    required TResult Function(BigInt posMs, bool playing, BigInt seq) adopt,
    required TResult Function(double rate) nudge,
    required TResult Function(BigInt posMs) seek,
  }) {
    final _that = this;
    switch (_that) {
      case SyncVerdict_Hold():
        return hold();
      case SyncVerdict_Adopt():
        return adopt(_that.posMs, _that.playing, _that.seq);
      case SyncVerdict_Nudge():
        return nudge(_that.rate);
      case SyncVerdict_Seek():
        return seek(_that.posMs);
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function()? hold,
    TResult? Function(BigInt posMs, bool playing, BigInt seq)? adopt,
    TResult? Function(double rate)? nudge,
    TResult? Function(BigInt posMs)? seek,
  }) {
    final _that = this;
    switch (_that) {
      case SyncVerdict_Hold() when hold != null:
        return hold();
      case SyncVerdict_Adopt() when adopt != null:
        return adopt(_that.posMs, _that.playing, _that.seq);
      case SyncVerdict_Nudge() when nudge != null:
        return nudge(_that.rate);
      case SyncVerdict_Seek() when seek != null:
        return seek(_that.posMs);
      case _:
        return null;
    }
  }
}

/// @nodoc

class SyncVerdict_Hold extends SyncVerdict {
  const SyncVerdict_Hold() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is SyncVerdict_Hold);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'SyncVerdict.hold()';
  }
}

/// @nodoc

class SyncVerdict_Adopt extends SyncVerdict {
  const SyncVerdict_Adopt(
      {required this.posMs, required this.playing, required this.seq})
      : super._();

  final BigInt posMs;
  final bool playing;
  final BigInt seq;

  /// Create a copy of SyncVerdict
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $SyncVerdict_AdoptCopyWith<SyncVerdict_Adopt> get copyWith =>
      _$SyncVerdict_AdoptCopyWithImpl<SyncVerdict_Adopt>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is SyncVerdict_Adopt &&
            (identical(other.posMs, posMs) || other.posMs == posMs) &&
            (identical(other.playing, playing) || other.playing == playing) &&
            (identical(other.seq, seq) || other.seq == seq));
  }

  @override
  int get hashCode => Object.hash(runtimeType, posMs, playing, seq);

  @override
  String toString() {
    return 'SyncVerdict.adopt(posMs: $posMs, playing: $playing, seq: $seq)';
  }
}

/// @nodoc
abstract mixin class $SyncVerdict_AdoptCopyWith<$Res>
    implements $SyncVerdictCopyWith<$Res> {
  factory $SyncVerdict_AdoptCopyWith(
          SyncVerdict_Adopt value, $Res Function(SyncVerdict_Adopt) _then) =
      _$SyncVerdict_AdoptCopyWithImpl;
  @useResult
  $Res call({BigInt posMs, bool playing, BigInt seq});
}

/// @nodoc
class _$SyncVerdict_AdoptCopyWithImpl<$Res>
    implements $SyncVerdict_AdoptCopyWith<$Res> {
  _$SyncVerdict_AdoptCopyWithImpl(this._self, this._then);

  final SyncVerdict_Adopt _self;
  final $Res Function(SyncVerdict_Adopt) _then;

  /// Create a copy of SyncVerdict
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? posMs = null,
    Object? playing = null,
    Object? seq = null,
  }) {
    return _then(SyncVerdict_Adopt(
      posMs: null == posMs
          ? _self.posMs
          : posMs // ignore: cast_nullable_to_non_nullable
              as BigInt,
      playing: null == playing
          ? _self.playing
          : playing // ignore: cast_nullable_to_non_nullable
              as bool,
      seq: null == seq
          ? _self.seq
          : seq // ignore: cast_nullable_to_non_nullable
              as BigInt,
    ));
  }
}

/// @nodoc

class SyncVerdict_Nudge extends SyncVerdict {
  const SyncVerdict_Nudge({required this.rate}) : super._();

  final double rate;

  /// Create a copy of SyncVerdict
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $SyncVerdict_NudgeCopyWith<SyncVerdict_Nudge> get copyWith =>
      _$SyncVerdict_NudgeCopyWithImpl<SyncVerdict_Nudge>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is SyncVerdict_Nudge &&
            (identical(other.rate, rate) || other.rate == rate));
  }

  @override
  int get hashCode => Object.hash(runtimeType, rate);

  @override
  String toString() {
    return 'SyncVerdict.nudge(rate: $rate)';
  }
}

/// @nodoc
abstract mixin class $SyncVerdict_NudgeCopyWith<$Res>
    implements $SyncVerdictCopyWith<$Res> {
  factory $SyncVerdict_NudgeCopyWith(
          SyncVerdict_Nudge value, $Res Function(SyncVerdict_Nudge) _then) =
      _$SyncVerdict_NudgeCopyWithImpl;
  @useResult
  $Res call({double rate});
}

/// @nodoc
class _$SyncVerdict_NudgeCopyWithImpl<$Res>
    implements $SyncVerdict_NudgeCopyWith<$Res> {
  _$SyncVerdict_NudgeCopyWithImpl(this._self, this._then);

  final SyncVerdict_Nudge _self;
  final $Res Function(SyncVerdict_Nudge) _then;

  /// Create a copy of SyncVerdict
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? rate = null,
  }) {
    return _then(SyncVerdict_Nudge(
      rate: null == rate
          ? _self.rate
          : rate // ignore: cast_nullable_to_non_nullable
              as double,
    ));
  }
}

/// @nodoc

class SyncVerdict_Seek extends SyncVerdict {
  const SyncVerdict_Seek({required this.posMs}) : super._();

  final BigInt posMs;

  /// Create a copy of SyncVerdict
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $SyncVerdict_SeekCopyWith<SyncVerdict_Seek> get copyWith =>
      _$SyncVerdict_SeekCopyWithImpl<SyncVerdict_Seek>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is SyncVerdict_Seek &&
            (identical(other.posMs, posMs) || other.posMs == posMs));
  }

  @override
  int get hashCode => Object.hash(runtimeType, posMs);

  @override
  String toString() {
    return 'SyncVerdict.seek(posMs: $posMs)';
  }
}

/// @nodoc
abstract mixin class $SyncVerdict_SeekCopyWith<$Res>
    implements $SyncVerdictCopyWith<$Res> {
  factory $SyncVerdict_SeekCopyWith(
          SyncVerdict_Seek value, $Res Function(SyncVerdict_Seek) _then) =
      _$SyncVerdict_SeekCopyWithImpl;
  @useResult
  $Res call({BigInt posMs});
}

/// @nodoc
class _$SyncVerdict_SeekCopyWithImpl<$Res>
    implements $SyncVerdict_SeekCopyWith<$Res> {
  _$SyncVerdict_SeekCopyWithImpl(this._self, this._then);

  final SyncVerdict_Seek _self;
  final $Res Function(SyncVerdict_Seek) _then;

  /// Create a copy of SyncVerdict
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? posMs = null,
  }) {
    return _then(SyncVerdict_Seek(
      posMs: null == posMs
          ? _self.posMs
          : posMs // ignore: cast_nullable_to_non_nullable
              as BigInt,
    ));
  }
}

/// @nodoc
mixin _$TogetherContent {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is TogetherContent);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'TogetherContent()';
  }
}

/// @nodoc
class $TogetherContentCopyWith<$Res> {
  $TogetherContentCopyWith(
      TogetherContent _, $Res Function(TogetherContent) __);
}

/// Adds pattern-matching-related methods to [TogetherContent].
extension TogetherContentPatterns on TogetherContent {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(TogetherContent_LocalFile value)? localFile,
    TResult Function(TogetherContent_Youtube value)? youtube,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case TogetherContent_LocalFile() when localFile != null:
        return localFile(_that);
      case TogetherContent_Youtube() when youtube != null:
        return youtube(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(TogetherContent_LocalFile value) localFile,
    required TResult Function(TogetherContent_Youtube value) youtube,
  }) {
    final _that = this;
    switch (_that) {
      case TogetherContent_LocalFile():
        return localFile(_that);
      case TogetherContent_Youtube():
        return youtube(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(TogetherContent_LocalFile value)? localFile,
    TResult? Function(TogetherContent_Youtube value)? youtube,
  }) {
    final _that = this;
    switch (_that) {
      case TogetherContent_LocalFile() when localFile != null:
        return localFile(_that);
      case TogetherContent_Youtube() when youtube != null:
        return youtube(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(BigInt durationMs, String? label)? localFile,
    TResult Function(String videoId)? youtube,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case TogetherContent_LocalFile() when localFile != null:
        return localFile(_that.durationMs, _that.label);
      case TogetherContent_Youtube() when youtube != null:
        return youtube(_that.videoId);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(BigInt durationMs, String? label) localFile,
    required TResult Function(String videoId) youtube,
  }) {
    final _that = this;
    switch (_that) {
      case TogetherContent_LocalFile():
        return localFile(_that.durationMs, _that.label);
      case TogetherContent_Youtube():
        return youtube(_that.videoId);
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(BigInt durationMs, String? label)? localFile,
    TResult? Function(String videoId)? youtube,
  }) {
    final _that = this;
    switch (_that) {
      case TogetherContent_LocalFile() when localFile != null:
        return localFile(_that.durationMs, _that.label);
      case TogetherContent_Youtube() when youtube != null:
        return youtube(_that.videoId);
      case _:
        return null;
    }
  }
}

/// @nodoc

class TogetherContent_LocalFile extends TogetherContent {
  const TogetherContent_LocalFile({required this.durationMs, this.label})
      : super._();

  final BigInt durationMs;
  final String? label;

  /// Create a copy of TogetherContent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $TogetherContent_LocalFileCopyWith<TogetherContent_LocalFile> get copyWith =>
      _$TogetherContent_LocalFileCopyWithImpl<TogetherContent_LocalFile>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is TogetherContent_LocalFile &&
            (identical(other.durationMs, durationMs) ||
                other.durationMs == durationMs) &&
            (identical(other.label, label) || other.label == label));
  }

  @override
  int get hashCode => Object.hash(runtimeType, durationMs, label);

  @override
  String toString() {
    return 'TogetherContent.localFile(durationMs: $durationMs, label: $label)';
  }
}

/// @nodoc
abstract mixin class $TogetherContent_LocalFileCopyWith<$Res>
    implements $TogetherContentCopyWith<$Res> {
  factory $TogetherContent_LocalFileCopyWith(TogetherContent_LocalFile value,
          $Res Function(TogetherContent_LocalFile) _then) =
      _$TogetherContent_LocalFileCopyWithImpl;
  @useResult
  $Res call({BigInt durationMs, String? label});
}

/// @nodoc
class _$TogetherContent_LocalFileCopyWithImpl<$Res>
    implements $TogetherContent_LocalFileCopyWith<$Res> {
  _$TogetherContent_LocalFileCopyWithImpl(this._self, this._then);

  final TogetherContent_LocalFile _self;
  final $Res Function(TogetherContent_LocalFile) _then;

  /// Create a copy of TogetherContent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? durationMs = null,
    Object? label = freezed,
  }) {
    return _then(TogetherContent_LocalFile(
      durationMs: null == durationMs
          ? _self.durationMs
          : durationMs // ignore: cast_nullable_to_non_nullable
              as BigInt,
      label: freezed == label
          ? _self.label
          : label // ignore: cast_nullable_to_non_nullable
              as String?,
    ));
  }
}

/// @nodoc

class TogetherContent_Youtube extends TogetherContent {
  const TogetherContent_Youtube({required this.videoId}) : super._();

  final String videoId;

  /// Create a copy of TogetherContent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $TogetherContent_YoutubeCopyWith<TogetherContent_Youtube> get copyWith =>
      _$TogetherContent_YoutubeCopyWithImpl<TogetherContent_Youtube>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is TogetherContent_Youtube &&
            (identical(other.videoId, videoId) || other.videoId == videoId));
  }

  @override
  int get hashCode => Object.hash(runtimeType, videoId);

  @override
  String toString() {
    return 'TogetherContent.youtube(videoId: $videoId)';
  }
}

/// @nodoc
abstract mixin class $TogetherContent_YoutubeCopyWith<$Res>
    implements $TogetherContentCopyWith<$Res> {
  factory $TogetherContent_YoutubeCopyWith(TogetherContent_Youtube value,
          $Res Function(TogetherContent_Youtube) _then) =
      _$TogetherContent_YoutubeCopyWithImpl;
  @useResult
  $Res call({String videoId});
}

/// @nodoc
class _$TogetherContent_YoutubeCopyWithImpl<$Res>
    implements $TogetherContent_YoutubeCopyWith<$Res> {
  _$TogetherContent_YoutubeCopyWithImpl(this._self, this._then);

  final TogetherContent_Youtube _self;
  final $Res Function(TogetherContent_Youtube) _then;

  /// Create a copy of TogetherContent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? videoId = null,
  }) {
    return _then(TogetherContent_Youtube(
      videoId: null == videoId
          ? _self.videoId
          : videoId // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc
mixin _$UiError {
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is UiError);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'UiError()';
  }
}

/// @nodoc
class $UiErrorCopyWith<$Res> {
  $UiErrorCopyWith(UiError _, $Res Function(UiError) __);
}

/// Adds pattern-matching-related methods to [UiError].
extension UiErrorPatterns on UiError {
  /// A variant of `map` that fallback to returning `orElse`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(UiError_UnknownWorkspace value)? unknownWorkspace,
    TResult Function(UiError_Transition value)? transition,
    TResult Function(UiError_NoIdentity value)? noIdentity,
    TResult Function(UiError_StoreLocked value)? storeLocked,
    TResult Function(UiError_Crypto value)? crypto,
    TResult Function(UiError_Storage value)? storage,
    TResult Function(UiError_VaultLocked value)? vaultLocked,
    TResult Function(UiError_Engine value)? engine,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case UiError_UnknownWorkspace() when unknownWorkspace != null:
        return unknownWorkspace(_that);
      case UiError_Transition() when transition != null:
        return transition(_that);
      case UiError_NoIdentity() when noIdentity != null:
        return noIdentity(_that);
      case UiError_StoreLocked() when storeLocked != null:
        return storeLocked(_that);
      case UiError_Crypto() when crypto != null:
        return crypto(_that);
      case UiError_Storage() when storage != null:
        return storage(_that);
      case UiError_VaultLocked() when vaultLocked != null:
        return vaultLocked(_that);
      case UiError_Engine() when engine != null:
        return engine(_that);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// Callbacks receives the raw object, upcasted.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case final Subclass2 value:
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(UiError_UnknownWorkspace value) unknownWorkspace,
    required TResult Function(UiError_Transition value) transition,
    required TResult Function(UiError_NoIdentity value) noIdentity,
    required TResult Function(UiError_StoreLocked value) storeLocked,
    required TResult Function(UiError_Crypto value) crypto,
    required TResult Function(UiError_Storage value) storage,
    required TResult Function(UiError_VaultLocked value) vaultLocked,
    required TResult Function(UiError_Engine value) engine,
  }) {
    final _that = this;
    switch (_that) {
      case UiError_UnknownWorkspace():
        return unknownWorkspace(_that);
      case UiError_Transition():
        return transition(_that);
      case UiError_NoIdentity():
        return noIdentity(_that);
      case UiError_StoreLocked():
        return storeLocked(_that);
      case UiError_Crypto():
        return crypto(_that);
      case UiError_Storage():
        return storage(_that);
      case UiError_VaultLocked():
        return vaultLocked(_that);
      case UiError_Engine():
        return engine(_that);
    }
  }

  /// A variant of `map` that fallback to returning `null`.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case final Subclass value:
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(UiError_UnknownWorkspace value)? unknownWorkspace,
    TResult? Function(UiError_Transition value)? transition,
    TResult? Function(UiError_NoIdentity value)? noIdentity,
    TResult? Function(UiError_StoreLocked value)? storeLocked,
    TResult? Function(UiError_Crypto value)? crypto,
    TResult? Function(UiError_Storage value)? storage,
    TResult? Function(UiError_VaultLocked value)? vaultLocked,
    TResult? Function(UiError_Engine value)? engine,
  }) {
    final _that = this;
    switch (_that) {
      case UiError_UnknownWorkspace() when unknownWorkspace != null:
        return unknownWorkspace(_that);
      case UiError_Transition() when transition != null:
        return transition(_that);
      case UiError_NoIdentity() when noIdentity != null:
        return noIdentity(_that);
      case UiError_StoreLocked() when storeLocked != null:
        return storeLocked(_that);
      case UiError_Crypto() when crypto != null:
        return crypto(_that);
      case UiError_Storage() when storage != null:
        return storage(_that);
      case UiError_VaultLocked() when vaultLocked != null:
        return vaultLocked(_that);
      case UiError_Engine() when engine != null:
        return engine(_that);
      case _:
        return null;
    }
  }

  /// A variant of `when` that fallback to an `orElse` callback.
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return orElse();
  /// }
  /// ```

  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(String field0)? unknownWorkspace,
    TResult Function(String field0)? transition,
    TResult Function()? noIdentity,
    TResult Function()? storeLocked,
    TResult Function(String field0)? crypto,
    TResult Function(String field0)? storage,
    TResult Function()? vaultLocked,
    TResult Function(String field0)? engine,
    required TResult orElse(),
  }) {
    final _that = this;
    switch (_that) {
      case UiError_UnknownWorkspace() when unknownWorkspace != null:
        return unknownWorkspace(_that.field0);
      case UiError_Transition() when transition != null:
        return transition(_that.field0);
      case UiError_NoIdentity() when noIdentity != null:
        return noIdentity();
      case UiError_StoreLocked() when storeLocked != null:
        return storeLocked();
      case UiError_Crypto() when crypto != null:
        return crypto(_that.field0);
      case UiError_Storage() when storage != null:
        return storage(_that.field0);
      case UiError_VaultLocked() when vaultLocked != null:
        return vaultLocked();
      case UiError_Engine() when engine != null:
        return engine(_that.field0);
      case _:
        return orElse();
    }
  }

  /// A `switch`-like method, using callbacks.
  ///
  /// As opposed to `map`, this offers destructuring.
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case Subclass2(:final field2):
  ///     return ...;
  /// }
  /// ```

  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(String field0) unknownWorkspace,
    required TResult Function(String field0) transition,
    required TResult Function() noIdentity,
    required TResult Function() storeLocked,
    required TResult Function(String field0) crypto,
    required TResult Function(String field0) storage,
    required TResult Function() vaultLocked,
    required TResult Function(String field0) engine,
  }) {
    final _that = this;
    switch (_that) {
      case UiError_UnknownWorkspace():
        return unknownWorkspace(_that.field0);
      case UiError_Transition():
        return transition(_that.field0);
      case UiError_NoIdentity():
        return noIdentity();
      case UiError_StoreLocked():
        return storeLocked();
      case UiError_Crypto():
        return crypto(_that.field0);
      case UiError_Storage():
        return storage(_that.field0);
      case UiError_VaultLocked():
        return vaultLocked();
      case UiError_Engine():
        return engine(_that.field0);
    }
  }

  /// A variant of `when` that fallback to returning `null`
  ///
  /// It is equivalent to doing:
  /// ```dart
  /// switch (sealedClass) {
  ///   case Subclass(:final field):
  ///     return ...;
  ///   case _:
  ///     return null;
  /// }
  /// ```

  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(String field0)? unknownWorkspace,
    TResult? Function(String field0)? transition,
    TResult? Function()? noIdentity,
    TResult? Function()? storeLocked,
    TResult? Function(String field0)? crypto,
    TResult? Function(String field0)? storage,
    TResult? Function()? vaultLocked,
    TResult? Function(String field0)? engine,
  }) {
    final _that = this;
    switch (_that) {
      case UiError_UnknownWorkspace() when unknownWorkspace != null:
        return unknownWorkspace(_that.field0);
      case UiError_Transition() when transition != null:
        return transition(_that.field0);
      case UiError_NoIdentity() when noIdentity != null:
        return noIdentity();
      case UiError_StoreLocked() when storeLocked != null:
        return storeLocked();
      case UiError_Crypto() when crypto != null:
        return crypto(_that.field0);
      case UiError_Storage() when storage != null:
        return storage(_that.field0);
      case UiError_VaultLocked() when vaultLocked != null:
        return vaultLocked();
      case UiError_Engine() when engine != null:
        return engine(_that.field0);
      case _:
        return null;
    }
  }
}

/// @nodoc

class UiError_UnknownWorkspace extends UiError {
  const UiError_UnknownWorkspace(this.field0) : super._();

  final String field0;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $UiError_UnknownWorkspaceCopyWith<UiError_UnknownWorkspace> get copyWith =>
      _$UiError_UnknownWorkspaceCopyWithImpl<UiError_UnknownWorkspace>(
          this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is UiError_UnknownWorkspace &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'UiError.unknownWorkspace(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $UiError_UnknownWorkspaceCopyWith<$Res>
    implements $UiErrorCopyWith<$Res> {
  factory $UiError_UnknownWorkspaceCopyWith(UiError_UnknownWorkspace value,
          $Res Function(UiError_UnknownWorkspace) _then) =
      _$UiError_UnknownWorkspaceCopyWithImpl;
  @useResult
  $Res call({String field0});
}

/// @nodoc
class _$UiError_UnknownWorkspaceCopyWithImpl<$Res>
    implements $UiError_UnknownWorkspaceCopyWith<$Res> {
  _$UiError_UnknownWorkspaceCopyWithImpl(this._self, this._then);

  final UiError_UnknownWorkspace _self;
  final $Res Function(UiError_UnknownWorkspace) _then;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(UiError_UnknownWorkspace(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class UiError_Transition extends UiError {
  const UiError_Transition(this.field0) : super._();

  final String field0;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $UiError_TransitionCopyWith<UiError_Transition> get copyWith =>
      _$UiError_TransitionCopyWithImpl<UiError_Transition>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is UiError_Transition &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'UiError.transition(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $UiError_TransitionCopyWith<$Res>
    implements $UiErrorCopyWith<$Res> {
  factory $UiError_TransitionCopyWith(
          UiError_Transition value, $Res Function(UiError_Transition) _then) =
      _$UiError_TransitionCopyWithImpl;
  @useResult
  $Res call({String field0});
}

/// @nodoc
class _$UiError_TransitionCopyWithImpl<$Res>
    implements $UiError_TransitionCopyWith<$Res> {
  _$UiError_TransitionCopyWithImpl(this._self, this._then);

  final UiError_Transition _self;
  final $Res Function(UiError_Transition) _then;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(UiError_Transition(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class UiError_NoIdentity extends UiError {
  const UiError_NoIdentity() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is UiError_NoIdentity);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'UiError.noIdentity()';
  }
}

/// @nodoc

class UiError_StoreLocked extends UiError {
  const UiError_StoreLocked() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is UiError_StoreLocked);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'UiError.storeLocked()';
  }
}

/// @nodoc

class UiError_Crypto extends UiError {
  const UiError_Crypto(this.field0) : super._();

  final String field0;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $UiError_CryptoCopyWith<UiError_Crypto> get copyWith =>
      _$UiError_CryptoCopyWithImpl<UiError_Crypto>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is UiError_Crypto &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'UiError.crypto(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $UiError_CryptoCopyWith<$Res>
    implements $UiErrorCopyWith<$Res> {
  factory $UiError_CryptoCopyWith(
          UiError_Crypto value, $Res Function(UiError_Crypto) _then) =
      _$UiError_CryptoCopyWithImpl;
  @useResult
  $Res call({String field0});
}

/// @nodoc
class _$UiError_CryptoCopyWithImpl<$Res>
    implements $UiError_CryptoCopyWith<$Res> {
  _$UiError_CryptoCopyWithImpl(this._self, this._then);

  final UiError_Crypto _self;
  final $Res Function(UiError_Crypto) _then;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(UiError_Crypto(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class UiError_Storage extends UiError {
  const UiError_Storage(this.field0) : super._();

  final String field0;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $UiError_StorageCopyWith<UiError_Storage> get copyWith =>
      _$UiError_StorageCopyWithImpl<UiError_Storage>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is UiError_Storage &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'UiError.storage(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $UiError_StorageCopyWith<$Res>
    implements $UiErrorCopyWith<$Res> {
  factory $UiError_StorageCopyWith(
          UiError_Storage value, $Res Function(UiError_Storage) _then) =
      _$UiError_StorageCopyWithImpl;
  @useResult
  $Res call({String field0});
}

/// @nodoc
class _$UiError_StorageCopyWithImpl<$Res>
    implements $UiError_StorageCopyWith<$Res> {
  _$UiError_StorageCopyWithImpl(this._self, this._then);

  final UiError_Storage _self;
  final $Res Function(UiError_Storage) _then;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(UiError_Storage(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class UiError_VaultLocked extends UiError {
  const UiError_VaultLocked() : super._();

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType && other is UiError_VaultLocked);
  }

  @override
  int get hashCode => runtimeType.hashCode;

  @override
  String toString() {
    return 'UiError.vaultLocked()';
  }
}

/// @nodoc

class UiError_Engine extends UiError {
  const UiError_Engine(this.field0) : super._();

  final String field0;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @pragma('vm:prefer-inline')
  $UiError_EngineCopyWith<UiError_Engine> get copyWith =>
      _$UiError_EngineCopyWithImpl<UiError_Engine>(this, _$identity);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is UiError_Engine &&
            (identical(other.field0, field0) || other.field0 == field0));
  }

  @override
  int get hashCode => Object.hash(runtimeType, field0);

  @override
  String toString() {
    return 'UiError.engine(field0: $field0)';
  }
}

/// @nodoc
abstract mixin class $UiError_EngineCopyWith<$Res>
    implements $UiErrorCopyWith<$Res> {
  factory $UiError_EngineCopyWith(
          UiError_Engine value, $Res Function(UiError_Engine) _then) =
      _$UiError_EngineCopyWithImpl;
  @useResult
  $Res call({String field0});
}

/// @nodoc
class _$UiError_EngineCopyWithImpl<$Res>
    implements $UiError_EngineCopyWith<$Res> {
  _$UiError_EngineCopyWithImpl(this._self, this._then);

  final UiError_Engine _self;
  final $Res Function(UiError_Engine) _then;

  /// Create a copy of UiError
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  $Res call({
    Object? field0 = null,
  }) {
    return _then(UiError_Engine(
      null == field0
          ? _self.field0
          : field0 // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

// dart format on
