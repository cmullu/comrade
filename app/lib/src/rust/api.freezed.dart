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
