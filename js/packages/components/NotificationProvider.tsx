import React from 'react'
import { InAppNotificationProvider, withInAppNotification } from 'react-native-in-app-notification'

import { useMessengerContext } from '@berty-tech/store/context'

import NotificationBody from './NotificationBody'
import { NativeEventEmitter, NativeModules, Platform } from 'react-native'
import { accountService } from '@berty-tech/store'
import beapi from '@berty-tech/api'
import { useNavigation } from '@berty-tech/navigation'

export const PushNotificationBridge: React.FC = withInAppNotification(
	({ showNotification }: any) => {
		const ctx = useMessengerContext()
		const { navigate } = useNavigation()

		React.useEffect(() => {
			const pushNotifListener = async (data: any) => {
				const push = await accountService.pushReceive({
					payload: data,
					tokenType:
						Platform.OS === 'ios'
							? beapi.push.PushServiceTokenType.PushTokenApplePushNotificationService
							: beapi.push.PushServiceTokenType.PushTokenFirebaseCloudMessaging,
				})
				const reply = await ctx.client?.parseDeepLink({ link: push.pushData?.deepLink })
				const intes = ctx.interactions[reply?.link?.bertyMessageRef?.groupPk as string]
				const inte = intes.find(i => i.cid === reply?.link?.bertyMessageRef?.messageId)
				if (!push.pushData?.alreadyReceived) {
					const convPK = push.pushData?.conversationPublicKey
					if (convPK) {
						const conv = ctx.conversations[convPK]
						const title =
							conv?.type === beapi.messenger.Conversation.Type.MultiMemberType
								? conv.displayName
								: Object.values(ctx.contacts).find((c: any) => c.conversationPublicKey === convPK)
										?.displayName
						showNotification({
							title,
							message:
								inte?.type === beapi.messenger.AppMessage.Type.TypeUserMessage
									? inte?.payload?.body
									: null,
							onPress: () => {
								navigate({
									name:
										conv?.type === beapi.messenger.Conversation.Type.MultiMemberType
											? 'Chat.Group'
											: 'Chat.OneToOne',
									params: {
										convId: convPK,
									},
								})
							},
							additionalProps: { type: 'message' },
						})
					}
				}
			}
			if (NativeModules.EventEmitter) {
				try {
					var eventListener = new NativeEventEmitter(NativeModules.EventEmitter).addListener(
						'onPushReceived',
						pushNotifListener,
					)
				} catch (e) {
					console.warn('Push notif add listener failed: ' + e)
				}
			}
			return () => {
				try {
					eventListener.remove() // Unsubscribe from native event emitter
				} catch (e) {
					console.warn('Push notif remove listener failed: ' + e)
				}
			}
		}, [ctx.client, ctx.contacts, ctx.conversations, ctx.interactions, navigate, showNotification])
		return null
	},
)

const NotificationBridge: React.FC = withInAppNotification(({ showNotification }: any) => {
	const { addNotificationListener, removeNotificationListener } = useMessengerContext()

	React.useEffect(() => {
		const inAppNotifListener = (evt: any) => {
			showNotification({
				title: evt.payload.title,
				message: evt.payload.body,
				onPress: evt.payload.onPress,
				additionalProps: evt,
			})
		}

		try {
			addNotificationListener(inAppNotifListener)
		} catch (e) {
			console.log('Error: Push notif add listener failed: ' + e)
		}

		return () => {
			removeNotificationListener(() => console.log('DELETE inAppNotifListener'))
		}
	}, [showNotification, addNotificationListener, removeNotificationListener])
	return null
})

const NotificationProvider: React.FC = ({ children }) => (
	<InAppNotificationProvider
		notificationBodyComponent={NotificationBody}
		backgroundColour='transparent'
		closeInterval={5000}
	>
		<>
			<NotificationBridge />
			<PushNotificationBridge />
			{children}
		</>
	</InAppNotificationProvider>
)

export default NotificationProvider
