# Salas do Orb

A seção Salas fica na Biblioteca, abaixo de Neste dispositivo. Usuários autenticados podem criar uma sala com título, capa opcional da galeria e até 5 membros no plano gratuito ou 15 membros no Orb Premium, contando o anfitrião. O código aleatório tem seis caracteres alfanuméricos e pode ser copiado ou compartilhado.

Após criar, o anfitrião seleciona sugestões ou busca músicas, ou continua sem selecionar. Uma animação de capas precede o convite. É permitido criar várias salas abertas. Cada usuário possui somente uma sala ativa para reprodução. Criar ou organizar outra sala não interrompe a atual. O servidor bloqueia a ativação ou reprodução de outra enquanto uma sala do anfitrião estiver tocando; é necessário pausá-la ou encerrá-la primeiro.

A sala aceita até 100 faixas do YouTube Music. O anfitrião controla a fila e a reprodução; convidados acompanham e podem sair.

A página principal apresenta play/pause e detalhes no topo, capa (ou apenas título) no centro com fotos dos membros flutuando ao redor, e 15 músicas no carrossel seguidas de um botão de adicionar. Ver todas abre uma sheet com a fila completa em três colunas. A página de detalhes reúne capa, título, membros, data de criação, músicas e encerramento com confirmação.

Salas gratuitas permitem que todos os membros adicionem músicas. Ao criar uma sala Premium, o anfitrião escolhe entre somente o anfitrião ou todos os membros. Adições são atômicas no servidor; convidados não podem substituir ou remover a fila nem comandar a reprodução.

A função orb-room-create valida a assinatura no endpoint autenticado existente do Render e emite um ticket de criação válido por um minuto e uma única utilização. Nenhuma indicação de Premium enviada pelo app é aceita como prova. Se a validação falhar, o app mostra um erro para tentar novamente.

Ao entrar como convidado, uma animação automática de 4,4 segundos gira as capas existentes em profundidade, começando devagar e acelerando até abrir a página principal. Salas sem músicas mostram uma breve apresentação do título e da capa, quando disponível.

Após a animação das capas, uma apresentação de boas-vindas de 3,8 segundos destaca a foto do usuário conectado e reúne os demais membros ao redor. Um som original de transição e confirmação acompanha a apresentação, sem solicitar foco de áudio. As respostas táteis respeitam a preferência do perfil. O som é liberado ao sair da animação. Só então a página principal da sala abre.

As sugestões de seleção usam rádios derivadas das músicas já presentes na sala e das escolhas recentes do usuário, com mistura de artistas, exclusão de músicas existentes/selecionadas e atualização conforme as escolhas. Não usam o cache de sugestões da Home nem o histórico local. Sem nenhuma faixa inicial, o usuário busca a primeira música. O carrossel ocupa a largura da tela e responde com toques táteis ao mudar a capa central.

Entrar ou abrir uma sala não inicia a reprodução no dispositivo do convidado. A página mostra se a sala está tocando ou pausada. Apenas ao tocar em Juntar-se o usuário ativa a escuta e acompanha a posição atual da sala.

O serviço renova a presença do anfitrião a cada dez segundos, mesmo com a sala pausada. Consultar uma sala ou a lista também renova a presença do dispositivo anfitrião. A ausência só é sinalizada após sessenta segundos sem contato, sem confundir pausa com desconexão.

## Servidor

O SQL orb_listening_rooms.sql foi aplicado ao Supabase do projeto. Autenticação, capacidade e permissões são validadas no servidor; capas são privadas. A função orb-delete-account também remove as capas das salas ao excluir a conta. Não há alteração necessária no Render.

As salas expiram em 24 horas. Convidados desconectados liberam vagas após dois minutos. Apenas o dispositivo anfitrião publica o estado de reprodução. A sincronização acompanha o relógio do anfitrião, com tolerância maior e defasagem intencional de até cinco segundos em dados móveis. O Automix é planejado pelo anfitrião com a versão habilitada em seu perfil; convidados não planejam transições nem usam suas próprias opções de Automix. O plano A→B (tempo, estilo e envelope) é publicado separadamente e reproduzido pelo motor de áudio normal do Orb. O servidor mantém até quatro receitas recentes para cobrir a defasagem. Convidados que não receberem um plano compatível continuam a reprodução sem mixagem independente.

Para habilitar as receitas compartilhadas, aplique **20261017_room_host_automix.sql** no Supabase depois das migrações das Salas. O novo RPC exige um membro autenticado para leitura e o anfitrião no dispositivo original para publicação. Instale a versão 1.7.2 atualizada em todos os aparelhos da Sala; versões antigas não têm suporte ao novo protocolo e não podem ser obrigadas remotamente a reproduzir uma receita que não conhecem.

## Validação

orb_listening_rooms_tests.sql passou em uma transação revertida, verificando criação, código, capacidade, permissões, limite de tentativas e encerramento. Sintaxe dos novos arquivos Kotlin e XML validada. Este pacote de fontes não contém o projeto Gradle completo: a compilação do APK e os testes de reprodução e animação em dois aparelhos ainda precisam ser realizados.
